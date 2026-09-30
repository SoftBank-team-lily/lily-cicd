package com.lily.cicd.deploy;

import com.lily.cicd.release.DeployConflictException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * 팀원이 HTTP 로 배포와 롤백을 걸 수 있는 입구.
 * 실제 순서는 {@link DeploymentEngine}, {@link RollbackEngine} 이 {@link DeploymentStrategy} 에 맡긴다.
 */
@RestController
@RequestMapping("/api/deployments")
public class DeployController {

    private final DeploymentEngine deployer;
    private final RollbackEngine rollbacker;

    public DeployController(DeploymentEngine deployer, RollbackEngine rollbacker) {
        this.deployer = deployer;
        this.rollbacker = rollbacker;
    }

    @PostMapping
    public CompletableFuture<ResponseEntity<Object>> deploy(@Valid @RequestBody DeployRequest request) {
        return respond(() -> deployer.deploy(request.toCommand()));
    }

    /** 직전 릴리스로 앱과 스키마를 되돌린다. docs/schema-migration.md 4 절 */
    @PostMapping("/{appName}/rollback")
    public CompletableFuture<ResponseEntity<Object>> rollback(
            @PathVariable String appName, @RequestBody(required = false) RollbackRequest request) {
        RollbackRequest body = request == null ? new RollbackRequest(null, false) : request;
        return respond(() -> rollbacker.rollback(appName, body.namespace(), body.appOnly()));
    }

    /** 슬롯별 릴리스와 롤백 가능 여부 */
    @GetMapping("/{appName}")
    public ResponseEntity<Object> status(@PathVariable String appName, @RequestParam(required = false) String namespace) {
        try {
            return ResponseEntity.ok(rollbacker.status(appName, namespace));
        } catch (RuntimeException e) {
            return ResponseEntity.status(statusOf(e)).body(errorBody(e));
        }
    }

    private CompletableFuture<ResponseEntity<Object>> respond(
            Supplier<CompletableFuture<DeploymentResultDto>> action) {
        CompletableFuture<DeploymentResultDto> future;
        try {
            future = action.get();
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        return future.handle((result, error) -> {
            if (error != null) {
                return ResponseEntity.status(statusOf(error)).body(errorBody(error));
            }
            return ResponseEntity.ok(result);
        });
    }

    private static int statusOf(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof IllegalArgumentException || cause instanceof UnsupportedOperationException) {
            return 400;
        }
        if (cause instanceof DeployConflictException) {
            return 409;
        }
        return 500;
    }

    private static DeployError errorBody(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof DeploymentFailedException failed) {
            return new DeployError("FAILED", failed.getMessage(), failed.getLogs());
        }
        if (cause instanceof DeployConflictException conflict) {
            return new DeployError("REJECTED", conflict.getMessage(), conflict.getLogs());
        }
        return new DeployError("FAILED", cause.getMessage() == null ? "deploy failed" : cause.getMessage(), List.of());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record DeployError(String status, String message, List<String> logs) {
    }

    /**
     * @param namespace 비우면 기본 namespace
     * @param appOnly   true 면 스키마는 그대로 두고 앱만 되돌린다
     */
    public record RollbackRequest(String namespace, boolean appOnly) {
    }
}
