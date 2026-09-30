package com.lily.cicd.deploy;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 팀원이 HTTP 로 배포를 걸 수 있는 입구.
 * 실제 순서는 {@link K8sBlueGreenDeployer} 에 있다.
 */
@RestController
@RequestMapping("/api/deployments")
public class DeployController {

    private final K8sBlueGreenDeployer deployer;

    public DeployController(K8sBlueGreenDeployer deployer) {
        this.deployer = deployer;
    }

    @PostMapping
    public CompletableFuture<ResponseEntity<Object>> deploy(@Valid @RequestBody DeployRequest request) {
        return deployer.deploy(request.toCommand()).handle((result, error) -> {
            if (error != null) {
                return ResponseEntity.status(statusOf(error)).body(errorBody(error));
            }
            return ResponseEntity.ok(result);
        });
    }

    private static int statusOf(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof IllegalArgumentException) {
            return 400;
        }
        return 500;
    }

    private static DeployError errorBody(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof DeploymentFailedException failed) {
            return new DeployError("FAILED", failed.getMessage(), failed.getLogs());
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
}
