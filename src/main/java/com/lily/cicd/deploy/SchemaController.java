package com.lily.cicd.deploy;

import com.lily.cicd.release.DeployConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** pgroll 롤백 창을 닫는다. docs/schema-migration.md 7 절 */
@RestController
@RequestMapping("/api/deployments")
public class SchemaController {

    private final PgrollCompleter completer;

    public SchemaController(PgrollCompleter completer) {
        this.completer = completer;
    }

    /** 롤백 창을 기다리지 않고 진행 중인 pgroll 마이그레이션을 complete 한다. 이후에는 스키마를 되돌릴 수 없다 */
    @PostMapping("/{appName}/schema/complete")
    public ResponseEntity<Object> complete(@PathVariable String appName,
                                           @RequestParam(required = false) String namespace) {
        DeploymentEngine.validateAppName(appName);
        String ns = namespace == null || namespace.isBlank() ? "default" : namespace;
        try {
            List<String> logs = completer.completeNow(ns, appName);
            return ResponseEntity.ok(new CompleteResult("COMPLETED", appName, logs));
        } catch (DeployConflictException e) {
            return ResponseEntity.status(409).body(new DeployController.DeployError("REJECTED", e.getMessage(), List.of()));
        }
    }

    public record CompleteResult(String status, String appName, List<String> logs) {
    }
}
