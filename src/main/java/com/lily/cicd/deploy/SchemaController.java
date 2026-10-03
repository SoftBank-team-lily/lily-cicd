package com.lily.cicd.deploy;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.release.ReleaseStore.Release;
import com.lily.cicd.schema.DbTarget;
import com.lily.cicd.schema.SchemaHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 앱의 스키마 이력과 pgroll 롤백 창. 프로젝트 상세 화면의 스키마 이력 패널이 쓴다 */
@RestController
@RequestMapping("/api/deployments")
public class SchemaController {

    private static final Logger log = LoggerFactory.getLogger(SchemaController.class);
    private static final List<String> SLOTS = List.of("stable", "canary", "blue", "green");

    private final PgrollCompleter completer;
    private final ReleaseStore releaseStore;
    private final DatabaseProvisioner databaseProvisioner;
    private final SchemaHistory history;

    @Autowired
    public SchemaController(PgrollCompleter completer, ReleaseStore releaseStore,
                            DatabaseProvisioner databaseProvisioner) {
        this(completer, releaseStore, databaseProvisioner, new SchemaHistory());
    }

    SchemaController(PgrollCompleter completer, ReleaseStore releaseStore, DatabaseProvisioner databaseProvisioner,
                     SchemaHistory history) {
        this.completer = completer;
        this.releaseStore = releaseStore;
        this.databaseProvisioner = databaseProvisioner;
        this.history = history;
    }

    /**
     * 슬롯별 스키마 버전, 열린 롤백 창, DB 의 이력(pgroll 또는 Flyway).
     * DB 에 붙지 못하면 이력만 비우고 {@code message} 에 이유를 담는다. 앱이 없으면 404
     */
    @GetMapping("/{appName}/schema")
    public ResponseEntity<SchemaView> schema(@PathVariable String appName,
                                             @RequestParam(required = false) String namespace) {
        DeploymentEngine.validateAppName(appName);
        String ns = namespace(namespace);
        List<Release> slots = SLOTS.stream()
                .map(slot -> releaseStore.read(ns, appName, slot))
                .flatMap(Optional::stream)
                .toList();
        if (slots.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Release current = slots.stream()
                .filter(r -> r.replicas() > 0 && r.deployedAt() != null)
                .max(Comparator.comparing(Release::deployedAt))
                .orElse(slots.get(0));
        Window window = slots.stream()
                .filter(r -> PgrollSchema.ACTIVE.equals(r.pgrollState()))
                .findFirst()
                .map(r -> new Window(r.schemaVersion(), r.slot(), r.pgrollCompleteAfter()))
                .orElse(null);
        List<Slot> slotViews = slots.stream()
                .map(r -> new Slot(r.slot(), r.schemaVersion(), r.replicas(), r.deployedAt(), r.pgrollState()))
                .toList();
        String database = slots.stream().map(Release::database).filter(d -> d != null && !d.isBlank())
                .findFirst().orElse(null);
        String engine = current.pgroll() ? ReleaseStore.PGROLL : current.schemaManaged() ? "flyway" : null;
        if (database == null) {
            return ResponseEntity.ok(new SchemaView(appName, null, engine, current.schemaVersion(), window, slotViews,
                    List.of(), "DB 를 쓰지 않는 앱이다"));
        }
        try {
            DeployContext context = new DeployContext(appName, ns, null, 0, DeploymentEngine.SERVICE_PORT,
                    appName + ".schema", "schema", current.slot(), DeploymentEngine.serviceName(appName),
                    DeploymentEngine.METRICS_PATH, database);
            Map<String, String> env = databaseProvisioner.prepare(context);
            SchemaHistory.History read = history.read(DbTarget.from(env));
            return ResponseEntity.ok(new SchemaView(appName, database, read.engine() != null ? read.engine() : engine,
                    current.schemaVersion(), window, slotViews, read.entries(), null));
        } catch (RuntimeException e) {
            log.warn("schema history lookup failed. app={} message={}", appName, e.getMessage());
            return ResponseEntity.ok(new SchemaView(appName, database, engine, current.schemaVersion(), window,
                    slotViews, List.of(), "DB 이력을 읽지 못했다"));
        }
    }

    /** 롤백 창을 기다리지 않고 진행 중인 pgroll 마이그레이션을 complete 한다. 이후에는 스키마를 되돌릴 수 없다 */
    @PostMapping("/{appName}/schema/complete")
    public ResponseEntity<Object> complete(@PathVariable String appName,
                                           @RequestParam(required = false) String namespace) {
        DeploymentEngine.validateAppName(appName);
        try {
            List<String> logs = completer.completeNow(namespace(namespace), appName);
            return ResponseEntity.ok(new CompleteResult("COMPLETED", appName, logs));
        } catch (DeployConflictException e) {
            return ResponseEntity.status(409).body(new DeployController.DeployError("REJECTED", e.getMessage(), List.of()));
        }
    }

    private static String namespace(String namespace) {
        return namespace == null || namespace.isBlank() ? "default" : namespace;
    }

    public record CompleteResult(String status, String appName, List<String> logs) {
    }

    /**
     * @param engine         {@code pgroll} / {@code flyway} / null (플랫폼이 스키마를 맡지 않는 앱)
     * @param currentVersion 지금 트래픽을 받는 슬롯의 스키마 버전
     * @param window         열린 pgroll 롤백 창. 없으면 null
     * @param history        DB 의 이력. 오래된 것부터
     * @param message        이력을 비운 이유. 정상이면 null
     */
    public record SchemaView(String appName, String database, String engine, String currentVersion, Window window,
                             List<Slot> slots, List<SchemaHistory.Entry> history, String message) {
    }

    /** @param completeAfter 이 시각이 지나면 complete. 그 전까지 스키마까지 롤백할 수 있다 */
    public record Window(String migration, String slot, Instant completeAfter) {
    }

    public record Slot(String slot, String schemaVersion, int replicas, Instant deployedAt, String pgrollState) {
    }
}
