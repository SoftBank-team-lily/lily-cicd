package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.DeployStages;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.release.ReleaseStore.Release;
import com.lily.cicd.schema.MigrationSet;
import com.lily.cicd.schema.SchemaMigrator;
import com.lily.cicd.schema.SchemaVersions;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.lily.cicd.deploy.DeploymentEngine.firstNonBlank;
import static com.lily.cicd.deploy.StageRecorder.last;

/**
 * 직전 릴리스(N-1)로 앱과 스키마를 함께 되돌린다. 스키마만 되돌리고 행 데이터는 남긴다.
 *
 * <pre>
 * 잠금 → 사전 검사 → 이전 슬롯 1 → Ready → selector 전환 → 백업 → U 역순 → 현재 슬롯 0
 * </pre>
 *
 * <p>사전 검사에서 걸리면 아무것도 바꾸지 않고 409 를 던진다.
 * 트래픽을 옮긴 뒤 스키마 되돌리기가 실패하면 앱 롤백은 유지하고 {@code PARTIAL} 로 알린다.
 * 배포 lint 덕분에 새 스키마는 이전 코드와 호환되므로 서비스는 계속된다.
 */
@Service
public class RollbackEngine {

    private static final Logger log = LoggerFactory.getLogger(RollbackEngine.class);

    private final DeployProperties properties;
    private final DeploymentStrategy strategy;
    private final DatabaseProvisioner databaseProvisioner;
    private final SchemaMigrator schemaMigrator;
    private final ReleaseStore releaseStore;
    private final DeployLock deployLock;
    private final StageRecorder recorder;

    public RollbackEngine(
            DeployProperties properties,
            DeploymentStrategy strategy,
            DatabaseProvisioner databaseProvisioner,
            DeployLog deployLog,
            DeployMonitor deployMonitor,
            SchemaMigrator schemaMigrator,
            ReleaseStore releaseStore,
            DeployLock deployLock) {
        this.properties = properties;
        this.strategy = strategy;
        this.databaseProvisioner = databaseProvisioner;
        this.schemaMigrator = schemaMigrator;
        this.releaseStore = releaseStore;
        this.deployLock = deployLock;
        this.recorder = new StageRecorder(deployLog, deployMonitor);
    }

    /**
     * @param appOnly true 면 스키마는 그대로 두고 앱만 되돌린다 (되돌릴 수 없는 스키마일 때)
     */
    @Async
    public CompletableFuture<DeploymentResultDto> rollback(String appName, String namespace, boolean appOnly) {
        try {
            return CompletableFuture.completedFuture(execute(appName, namespace, appOnly));
        } catch (KubernetesClientException e) {
            log.error("kubernetes api failed during rollback. code={} message={}", e.getCode(), e.getMessage(), e);
            throw new DeploymentFailedException("kubernetes api 호출 실패: " + e.getMessage(), List.of(), e);
        }
    }

    /** 롤백 가능 여부만 본다. DB 에는 붙지 않고 릴리스 기록으로 판단한다 */
    public ReleaseStatus status(String appName, String namespace) {
        DeploymentEngine.validateAppName(appName);
        String ns = firstNonBlank(namespace, properties.getNamespace());
        List<String> ignored = new ArrayList<>();
        List<Release> slots = new ArrayList<>();
        SlotPlan plan;
        try {
            plan = strategy.planRollback(ns, appName, ignored);
        } catch (DeployConflictException | UnsupportedOperationException e) {
            for (String slot : List.of("blue", "green", "stable", "canary")) {
                releaseStore.read(ns, appName, slot).ifPresent(slots::add);
            }
            return new ReleaseStatus(appName, ns, null, slots, false, e.getMessage());
        }
        Optional<Release> current = releaseStore.read(ns, appName, plan.previous());
        Optional<Release> previous = releaseStore.read(ns, appName, plan.target());
        current.ifPresent(slots::add);
        previous.ifPresent(slots::add);
        Optional<String> blocker = current.isEmpty() || previous.isEmpty()
                ? Optional.of("슬롯 기록이 없다")
                : blocker(ns, appName, plan, current.get(), previous.get(), true, null);
        return new ReleaseStatus(appName, ns, plan.previous(), slots, blocker.isEmpty(), blocker.orElse(null));
    }

    private DeploymentResultDto execute(String appName, String namespace, boolean appOnly) {
        DeploymentEngine.validateAppName(appName);
        String ns = firstNonBlank(namespace, properties.getNamespace());
        String host = appName + "." + properties.getDomain();
        List<String> logs = new ArrayList<>();
        DeployContext context = new DeployContext(appName, ns, null, 0, DeploymentEngine.SERVICE_PORT, host,
                "rollback", null, DeploymentEngine.serviceName(appName), DeploymentEngine.METRICS_PATH, null);

        try (DeployLock.Handle lock = deployLock.acquire(ns, appName, "rollback")) {
            try (DeployLock.Beat beat = lock.heartbeat()) {
            recorder.record(context, logs, DeployStages.STARTED,
                    "rollback started strategy=" + strategy.name() + " appOnly=" + appOnly);
            SlotPlan plan = strategy.planRollback(ns, appName, logs);
            context = context.withTargetColor(plan.target());
            Release current = releaseStore.read(ns, appName, plan.previous())
                    .orElseThrow(() -> new DeployConflictException("현재 슬롯이 없다: " + plan.previous(), logs));
            Release previous = releaseStore.read(ns, appName, plan.target())
                    .orElseThrow(() -> new DeployConflictException("이전 슬롯이 없다: " + plan.target(), logs));

            SchemaTarget schema = null;
            if (!appOnly && current.schemaManaged()) {
                context = withDatabase(context, current.database());
                Map<String, String> databaseEnv = prepareDatabase(context, logs);
                schema = new SchemaTarget(databaseEnv, releaseStore.loadScripts(ns, appName, plan.previous()),
                        SchemaVersions.parse(previous.schemaVersion()));
            }
            Optional<String> blocked = blocker(ns, appName, plan, current, previous, !appOnly, schema);
            if (blocked.isPresent()) {
                logs.add("rollback: refused — " + blocked.get());
                throw new DeployConflictException("롤백할 수 없다: " + blocked.get(), logs);
            }
            recorder.record(context, logs, DeployStages.ROLLBACK, "rollback: precheck ok "
                    + current.slot() + "(" + current.schemaVersion() + ") -> "
                    + previous.slot() + "(" + previous.schemaVersion() + ")");

            strategy.restorePrevious(ns, appName, plan, logs);
            recorder.record(context, logs, DeployStages.SERVICE, last(logs));

            String status = "ROLLED_BACK";
            String schemaVersion = appOnly ? current.schemaVersion() : previous.schemaVersion();
            if (schema != null) {
                try {
                    schemaMigrator.rollback(schema.databaseEnv(), schema.scripts(), schema.target(), true, logs);
                    recorder.record(context, logs, DeployStages.MIGRATION, last(logs));
                } catch (RuntimeException e) {
                    log.error("schema rollback failed after traffic switch. app={} message={}",
                            appName, e.getMessage(), e);
                    status = "PARTIAL";
                    schemaVersion = current.schemaVersion();
                    recorder.record(context, logs, DeployStages.MIGRATION,
                            "schema: 되돌리기 실패, 앱 롤백은 유지 — " + e.getMessage());
                }
            }

            strategy.retirePrevious(ns, appName, plan, logs);
            recorder.record(context, logs, DeployStages.SCALE_DOWN, last(logs));
            recorder.record(context, logs, DeployStages.SUCCEEDED,
                    "rollback complete. active=" + plan.target() + " status=" + status);
            return new DeploymentResultDto(status, plan.target(), "http://" + host, schemaVersion, List.copyOf(logs));
            }
        } catch (RuntimeException e) {
            recorder.failed(context, logs, e);
            throw e;
        }
    }

    /**
     * 되돌릴 수 없는 이유. schema 가 있으면 DB 의 실제 버전으로 U 를 확인하고, 없으면 기록된 버전으로 본다.
     *
     * @param includeSchema false 면 (appOnly) 슬롯 순서만 본다
     */
    private Optional<String> blocker(String ns, String appName, SlotPlan plan, Release current, Release previous,
                                     boolean includeSchema, SchemaTarget schema) {
        if (current.deployedAt() == null || previous.deployedAt() == null) {
            return Optional.of("배포 시각 기록이 없는 슬롯이다 (이 기능 이전 배포)");
        }
        if (!previous.deployedAt().isBefore(current.deployedAt())) {
            return Optional.of(previous.slot() + " 가 " + current.slot() + " 보다 최신이다. 이미 롤백한 상태다");
        }
        if (!includeSchema || !current.schemaManaged()) {
            return Optional.empty();
        }
        if (!previous.schemaManaged()) {
            return Optional.of("이전 릴리스의 스키마 버전 기록이 없다. appOnly=true 로 앱만 되돌릴 수 있다");
        }
        MigrationVersion target = SchemaVersions.parse(previous.schemaVersion());
        List<MigrationVersion> versions;
        MigrationSet scripts;
        if (schema != null) {
            versions = schemaMigrator.versionsAbove(schema.databaseEnv(), target);
            scripts = schema.scripts();
        } else {
            scripts = releaseStore.loadScripts(ns, appName, plan.previous());
            MigrationVersion now = SchemaVersions.parse(current.schemaVersion());
            if (now.compareTo(target) > 0 && scripts.versioned(now).isEmpty()) {
                return Optional.of("v" + now + ": 현재 릴리스의 스크립트 기록이 없다. appOnly=true 로 앱만 되돌릴 수 있다");
            }
            versions = scripts.versionedScripts().stream()
                    .map(s -> s.version())
                    .filter(v -> v.compareTo(target) > 0 && v.compareTo(now) <= 0)
                    .toList();
        }
        return scripts.undoBlocker(versions).map(reason -> reason + ". appOnly=true 로 앱만 되돌릴 수 있다");
    }

    private Map<String, String> prepareDatabase(DeployContext context, List<String> logs) {
        try {
            Map<String, String> env = databaseProvisioner.prepare(context);
            recorder.record(context, logs, DeployStages.DATABASE, "database: env keys="
                    + (env == null ? "[]" : env.keySet()));
            return env == null ? Map.of() : env;
        } catch (RuntimeException e) {
            logs.add("database: prepare failed — 아무것도 바꾸지 않음");
            throw new DeploymentFailedException("DB 모듈 준비 실패", logs, e);
        }
    }

    private static DeployContext withDatabase(DeployContext c, String database) {
        return new DeployContext(c.appName(), c.namespace(), c.imageUrl(), c.targetPort(), c.servicePort(),
                c.host(), c.appVersion(), c.targetColor(), c.serviceName(), c.metricsPath(), database);
    }

    private record SchemaTarget(Map<String, String> databaseEnv, MigrationSet scripts, MigrationVersion target) {}

    /**
     * @param activeSlot        지금 트래픽을 받는 슬롯. blue-green 이 아니면 null
     * @param rollbackAvailable 지금 롤백하면 앱과 스키마를 모두 되돌릴 수 있는지 (DB 는 조회하지 않은 판단)
     * @param reason            불가능한 이유
     */
    public record ReleaseStatus(String appName, String namespace, String activeSlot, List<Release> slots,
                                boolean rollbackAvailable, String reason) {}
}
