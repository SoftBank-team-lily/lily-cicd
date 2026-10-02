package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.DeployStages;
import com.lily.cicd.module.TrafficRouter;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.MigrationSet;
import com.lily.cicd.schema.PgrollMigrator.PgrollChange;
import com.lily.cicd.schema.PgrollSet;
import com.lily.cicd.schema.SchemaChange;
import com.lily.cicd.schema.SchemaMigrator;
import com.lily.cicd.schema.SchemaOperationException;
import com.lily.cicd.schema.SchemaVersions;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.lily.cicd.deploy.StageRecorder.last;

/**
 * 배포 요청을 받아 DB 와 스키마를 준비한 뒤 {@link DeploymentStrategy} 에 클러스터 반영을 맡긴다.
 *
 * <p>기본 전략은 블루그린이다. Canary 등 다른 규칙은 {@code DeploymentStrategy} 빈을 등록하면 그 구현이 쓰인다.
 * Router, Logging, Monitoring 은 전략과 별도로 빈만 바꾸면 붙는다.
 *
 * <p>스키마 마이그레이션은 새 슬롯을 만들기 전에 적용하고, 트래픽을 옮기기 전에 배포가 실패하면 되돌린다.
 * 같은 앱의 배포와 롤백은 {@link DeployLock} 으로 한 번에 하나만 돈다. 순서와 규칙은 docs/schema-migration.md.
 *
 * <p>인스턴스 필드는 설정과 협력 객체뿐이라 동시에 여러 앱을 배포해도 상태가 섞이지 않는다.
 * 한 번의 실행 기록은 메서드 지역 변수 {@code logs} 에만 쌓인다.
 */
@org.springframework.stereotype.Service
public class DeploymentEngine {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEngine.class);
    static final int SERVICE_PORT = 80;
    static final String METRICS_PATH = "/actuator/prometheus";
    /** 플랫폼이 스키마를 맡는 앱은 자체 Flyway 를 끈다 (Spring Boot 가 읽는 이름) */
    static final String APP_FLYWAY_ENV = "SPRING_FLYWAY_ENABLED";

    private final DeployProperties properties;
    private final DeploymentStrategy strategy;
    private final DatabaseProvisioner databaseProvisioner;
    private final TrafficRouter trafficRouter;
    private final DeployMonitor deployMonitor;
    private final SchemaMigrator schemaMigrator;
    private final ReleaseStore releaseStore;
    private final DeployLock deployLock;
    private final StageRecorder recorder;
    /** null 이면 canary 판정 없이 바로 전환한다 */
    private final CanaryAnalysis canary;
    /** null 이면 프로세스가 죽어도 스키마를 되돌리지 않는다 */
    private final DeployRecovery recovery;
    /** null 이면 pgroll 마이그레이션을 받지 않는다 */
    private final PgrollSchema pgroll;

    /** canary 판정과 진행 상황 기록 없이 */
    public DeploymentEngine(
            DeployProperties properties,
            DeploymentStrategy strategy,
            DatabaseProvisioner databaseProvisioner,
            TrafficRouter trafficRouter,
            DeployLog deployLog,
            DeployMonitor deployMonitor,
            SchemaMigrator schemaMigrator,
            ReleaseStore releaseStore,
            DeployLock deployLock) {
        this(properties, strategy, databaseProvisioner, trafficRouter, deployLog, deployMonitor,
                schemaMigrator, releaseStore, deployLock, null, null, null);
    }

    /** pgroll 없이 */
    public DeploymentEngine(
            DeployProperties properties,
            DeploymentStrategy strategy,
            DatabaseProvisioner databaseProvisioner,
            TrafficRouter trafficRouter,
            DeployLog deployLog,
            DeployMonitor deployMonitor,
            SchemaMigrator schemaMigrator,
            ReleaseStore releaseStore,
            DeployLock deployLock,
            CanaryAnalysis canary,
            DeployProgress progress,
            DeployRecovery recovery) {
        this(properties, strategy, databaseProvisioner, trafficRouter, deployLog, deployMonitor,
                schemaMigrator, releaseStore, deployLock, canary, progress, recovery, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeploymentEngine(
            DeployProperties properties,
            DeploymentStrategy strategy,
            DatabaseProvisioner databaseProvisioner,
            TrafficRouter trafficRouter,
            DeployLog deployLog,
            DeployMonitor deployMonitor,
            SchemaMigrator schemaMigrator,
            ReleaseStore releaseStore,
            DeployLock deployLock,
            CanaryAnalysis canary,
            DeployProgress progress,
            DeployRecovery recovery,
            PgrollSchema pgroll) {
        this.pgroll = pgroll;
        this.properties = properties;
        this.strategy = strategy;
        this.databaseProvisioner = databaseProvisioner;
        this.trafficRouter = trafficRouter;
        this.deployMonitor = deployMonitor;
        this.schemaMigrator = schemaMigrator;
        this.releaseStore = releaseStore;
        this.deployLock = deployLock;
        this.recorder = new StageRecorder(deployLog, deployMonitor, progress);
        this.canary = canary;
        this.recovery = recovery;
    }

    @Async
    public CompletableFuture<DeploymentResultDto> deploy(String appName, String imageUrl, int targetPort) {
        return deploy(DeployCommand.of(appName, imageUrl, targetPort));
    }

    @Async
    public CompletableFuture<DeploymentResultDto> deploy(DeployCommand command) {
        try {
            return CompletableFuture.completedFuture(execute(command));
        } catch (DeploymentFailedException e) {
            log.error("deploy aborted. strategy={} message={}", strategy.name(), e.getMessage(), e);
            throw e;
        } catch (KubernetesClientException e) {
            log.error("kubernetes api failed. code={} message={}", e.getCode(), e.getMessage(), e);
            throw new DeploymentFailedException(
                    "kubernetes api 호출 실패: " + e.getMessage(), List.of(), e);
        }
    }

    private DeploymentResultDto execute(DeployCommand command) {
        command = applyDefaults(command);
        validate(command);
        boolean pgrollMode = PgrollSet.isPgroll(command.migrations());
        PgrollSet pgrollSet = pgrollMode ? PgrollSet.parse(command.migrations()) : PgrollSet.empty();
        MigrationSet scripts = pgrollMode ? MigrationSet.empty() : MigrationSet.parse(command.migrations());
        if (!scripts.isEmpty() && isBlank(command.database())) {
            throw new IllegalArgumentException("migrations 는 database 와 함께 보내야 한다");
        }
        if (pgrollMode && pgroll == null) {
            throw new IllegalArgumentException("이 lily-cicd 는 pgroll 마이그레이션을 받지 않는다");
        }
        if (pgrollMode && !"postgres".equals(command.database())) {
            throw new IllegalArgumentException("pgroll 마이그레이션은 database=postgres 와 함께 보내야 한다");
        }
        boolean givenDatabase = command.databaseEnv() != null && !command.databaseEnv().isEmpty();
        if (givenDatabase && !isBlank(command.database())) {
            throw new IllegalArgumentException("databaseEnv 는 database 와 같이 보낼 수 없다");
        }
        List<String> logs = new ArrayList<>();
        String namespace = firstNonBlank(command.namespace(), properties.getNamespace());
        String appName = command.appName();
        String host = firstNonBlank(command.host(),
                appName + "." + firstNonBlank(command.domain(), properties.getDomain()));
        DeployContext context = new DeployContext(
                appName,
                namespace,
                command.imageUrl(),
                command.targetPort(),
                SERVICE_PORT,
                host,
                firstNonBlank(command.appVersion(), "dev"),
                null,
                serviceName(appName),
                METRICS_PATH,
                command.database(),
                command.aliases());

        try (DeployLock.Handle lock = deployLock.acquire(namespace, appName, "deploy")) {
            try (DeployLock.Beat beat = lock.heartbeat();
                 DeployLock.Beat pulse = recorder.pulse(namespace, appName)) {
            recorder.record(context, logs, DeployStages.STARTED,
                    "deploy started strategy=" + strategy.name() + " image=" + command.imageUrl());
            Map<String, String> databaseEnv = givenDatabase
                    ? givenDatabase(context, command.databaseEnv(), logs)
                    : prepareDatabase(context, logs);

            SlotPlan plan = strategy.plan(namespace, appName, logs);
            context = strategy.bind(context, plan);
            recorder.record(context, logs, DeployStages.COLOR, last(logs));

            SchemaChange change = SchemaChange.NONE;
            PgrollChange pgrollChange = null;
            Map<String, String> appEnv = databaseEnv;
            if (pgrollMode) {
                boolean oldVersionLive = releaseStore.read(namespace, appName, plan.previous())
                        .map(r -> r.replicas() > 0).orElse(false);
                pgrollChange = migratePgroll(context, databaseEnv, pgrollSet, oldVersionLive, logs);
                appEnv = PgrollSchema.appEnv(databaseEnv, pgrollChange.to());
            } else {
                change = migrateSchema(context, databaseEnv, scripts, logs);
                if (!scripts.isEmpty()) {
                    appEnv = new LinkedHashMap<>(databaseEnv);
                    appEnv.put(APP_FLYWAY_ENV, "false");
                } else if (pgroll != null && "postgres".equals(command.database())) {
                    // 마이그레이션 파일이 없는 커밋도 pgroll 로 관리하던 DB 면 최신 버전 스키마로 붙는다
                    Optional<String> latest = pgroll.latest(databaseEnv);
                    if (latest.isPresent()) {
                        pgrollChange = new PgrollChange(latest.get(), latest.get(), false, null);
                        appEnv = PgrollSchema.appEnv(databaseEnv, latest.get());
                        logs.add("schema: pgroll 관리 DB, 마이그레이션 없음. " + PgrollSet.versionSchema(latest.get()) + " 로 접속");
                    }
                }
            }
            String schemaVersion = pgrollChange != null ? pgrollChange.to() : SchemaVersions.format(change.to());
            String schemaEngine = pgrollChange != null ? ReleaseStore.PGROLL : null;

            try {
                releaseStore.saveScripts(namespace, appName, plan.target(), scripts);
                if (recovery != null && !change.applied().isEmpty()) {
                    recovery.arm(namespace, appName, plan.target(),
                            SchemaVersions.format(change.from()), command.database());
                }
                if (recovery != null && pgrollChange != null && pgrollChange.started()) {
                    recovery.armPgroll(namespace, appName, plan.target(), pgrollChange.to(), command.database());
                }
                strategy.applyTarget(command, namespace, plan, appEnv, logs);
                recorder.record(context, logs, DeployStages.DEPLOYMENT, last(logs));
                releaseStore.annotate(namespace, appName, plan.target(), Instant.now(),
                        schemaVersion, command.database(), schemaEngine);

                strategy.awaitReady(namespace, appName, plan, logs);
                recorder.record(context, logs, DeployStages.READY, last(logs));

                CanaryAnalysis.Verdict verdict = judge(context, command, plan, logs);
                if (verdict.rejected()) {
                    strategy.discardTarget(namespace, appName, plan, logs);
                    throw new CanaryRejectedException("canary 판정 실패: " + verdict.reason(), logs);
                }
            } catch (RuntimeException e) {
                closeCanary(namespace, appName, logs);
                revertSchema(databaseEnv, scripts, change, logs, e);
                boolean pgrollReverted = revertPgroll(databaseEnv, pgrollChange, logs, e);
                if (recovery != null && pgrollReverted) {
                    recovery.clear(namespace, appName);
                }
                if (e instanceof CanaryRejectedException) {
                    throw new CanaryRejectedException(e.getMessage(), logs);
                }
                if ((!change.applied().isEmpty() || pgrollReverted) && e instanceof DeploymentFailedException failed) {
                    // 예외의 로그는 만들 때 복사된다. 되돌린 결과를 응답에 담으려고 다시 만든다
                    throw new DeploymentFailedException(failed.getMessage(), logs, failed.getCause());
                }
                throw e;
            }

            try {
                strategy.switchTraffic(command, namespace, plan, logs);
                if (recovery != null) {
                    recovery.clear(namespace, appName);
                }
            } finally {
                closeCanary(namespace, appName, logs);
            }
            recorder.record(context, logs, DeployStages.SERVICE, last(logs));
            if (pgrollChange != null && pgrollChange.started()) {
                pgroll.opened(namespace, appName, plan.target(), logs);
            }

            route(context, logs);
            watch(context, logs);
            strategy.retirePrevious(namespace, appName, plan, logs);
            recorder.record(context, logs, DeployStages.SCALE_DOWN, last(logs));

            String url = properties.getUrlScheme() + "://" + host;
            String active = strategy.finalSlot(plan);
            recorder.record(context, logs, DeployStages.SUCCEEDED,
                    "cutover complete. active=" + active + " host=" + host + " url=" + url);
            return new DeploymentResultDto("SUCCESS", active, url, schemaVersion, List.copyOf(logs));
            }
        } catch (RuntimeException e) {
            recorder.failed(context, logs, e);
            throw e;
        }
    }

    /**
     * 트래픽을 옮기기 전 에러율·p95 판정. 블루그린과 카나리 모두 한다.
     * 카나리는 사용자 비율을 올리기 전에 하고, 그동안 사용자 트래픽은 이전 슬롯에 남는다.
     * 판정 경로는 요청의 canaryPath, 없으면 readiness 경로다.
     * 첫 배포처럼 비교할 이전 버전이 없으면 건너뛴다.
     */
    private CanaryAnalysis.Verdict judge(DeployContext context, DeployCommand command, SlotPlan plan, List<String> logs) {
        if (canary == null || !("blue-green".equals(strategy.name()) || "canary".equals(strategy.name()))) {
            return new CanaryAnalysis.Verdict("SKIPPED", "not enabled for " + strategy.name(), null);
        }
        String path = firstNonBlank(command.canaryPath(),
                firstNonBlank(command.readinessPath(), properties.getReadinessPath()));
        if (Probes.isTcp(path)) {
            // 헬스 경로가 없는 앱. canary 는 5xx 만 실패로 세므로 / 의 401·404 는 괜찮다
            path = "/";
        }
        try {
            return canary.judge(context, path, plan, (stage, line) -> recorder.record(context, logs, stage, line));
        } catch (KubernetesClientException e) {
            log.error("canary analysis failed. app={} message={}", context.appName(), e.getMessage(), e);
            logs.add("canary: kubernetes api 실패 — " + e.getMessage());
            throw new DeploymentFailedException("canary 판정 준비 실패: " + e.getMessage(), logs, e);
        }
    }

    private void closeCanary(String namespace, String appName, List<String> logs) {
        if (canary != null) {
            canary.cleanup(namespace, appName, logs);
        }
    }

    /** 호출자가 준 DB 접속 정보 (온프레미스 DB 를 역방향 터널로 쓰는 대기 배포). 값은 로그에 남기지 않는다 */
    private Map<String, String> givenDatabase(DeployContext context, Map<String, String> env, List<String> logs) {
        recorder.record(context, logs, DeployStages.DATABASE, "database: given env keys=" + env.keySet());
        return env;
    }

    private Map<String, String> prepareDatabase(DeployContext context, List<String> logs) {
        try {
            Map<String, String> env = databaseProvisioner.prepare(context);
            if (env == null) {
                env = Map.of();
            }
            recorder.record(context, logs, DeployStages.DATABASE, "database: env keys=" + env.keySet());
            return env;
        } catch (RuntimeException e) {
            logs.add("database: prepare failed — deployment 를 만들지 않음");
            log.error("database module failed. app={} message={}", context.appName(), e.getMessage(), e);
            throw new DeploymentFailedException("DB 모듈 준비 실패", logs, e);
        }
    }

    /**
     * lint 위반은 400 (IllegalArgumentException 그대로), dry-run·migrate 실패는 배포 실패다.
     * 어느 쪽이든 슬롯은 아직 만들지 않았다.
     */
    private SchemaChange migrateSchema(DeployContext context, Map<String, String> databaseEnv,
                                       MigrationSet scripts, List<String> logs) {
        if (scripts.isEmpty()) {
            return SchemaChange.NONE;
        }
        try {
            SchemaChange change = schemaMigrator.migrate(databaseEnv, scripts, logs);
            recorder.record(context, logs, DeployStages.MIGRATION, last(logs));
            return change;
        } catch (SchemaOperationException | IllegalStateException e) {
            log.error("schema migration failed. app={} message={}", context.appName(), e.getMessage(), e);
            logs.add("schema: failed — deployment 를 만들지 않음");
            throw new DeploymentFailedException("스키마 마이그레이션 실패: " + e.getMessage(), logs, e);
        }
    }

    /**
     * 트래픽을 옮기기 전에 실패했으면 이번에 적용한 버전을 되돌린다.
     * 되돌리기 자체가 실패하면 배포 실패 이유에 스키마가 남은 버전을 붙인다.
     */
    private void revertSchema(Map<String, String> databaseEnv, MigrationSet scripts, SchemaChange change,
                              List<String> logs, RuntimeException deployError) {
        if (change.applied().isEmpty()) {
            return;
        }
        try {
            schemaMigrator.rollback(databaseEnv, scripts, change.from(), false, logs);
        } catch (RuntimeException e) {
            log.error("schema revert failed. message={}", e.getMessage(), e);
            String stuck = "스키마를 되돌리지 못함. 스키마는 " + SchemaVersions.format(change.to()) + " 에 남음";
            logs.add("schema: " + stuck + " — " + e.getMessage());
            throw new DeploymentFailedException(deployError.getMessage() + ". " + stuck, logs, e);
        }
    }

    /**
     * 규칙 위반은 400 (IllegalArgumentException 그대로), pgroll 켜기·start 실패는 배포 실패다.
     * 어느 쪽이든 슬롯은 아직 만들지 않았다.
     */
    private PgrollChange migratePgroll(DeployContext context, Map<String, String> databaseEnv, PgrollSet set,
                                       boolean oldVersionLive, List<String> logs) {
        try {
            PgrollChange change = pgroll.migrate(context, databaseEnv, set, oldVersionLive, logs);
            recorder.record(context, logs, DeployStages.MIGRATION, last(logs));
            return change;
        } catch (IllegalArgumentException e) {
            logs.add("schema: " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("pgroll migration failed. app={} message={}", context.appName(), e.getMessage(), e);
            logs.add("schema: failed — deployment 를 만들지 않음");
            throw new DeploymentFailedException("스키마 마이그레이션 실패: " + e.getMessage(), logs, e);
        }
    }

    /**
     * 트래픽을 옮기기 전에 실패했으면 이번 배포가 시작한 pgroll 마이그레이션을 되돌린다.
     * 새 슬롯은 이미 지워졌고 사용자 트래픽은 이전 슬롯(이전 버전 스키마)에 있다.
     *
     * @return 되돌렸으면 true
     */
    private boolean revertPgroll(Map<String, String> databaseEnv, PgrollChange change, List<String> logs,
                                 RuntimeException deployError) {
        if (change == null || !change.started()) {
            return false;
        }
        try {
            pgroll.rollback(databaseEnv, change.to(), logs);
            return true;
        } catch (RuntimeException e) {
            log.error("pgroll revert failed. message={}", e.getMessage(), e);
            String stuck = "스키마를 되돌리지 못함. pgroll " + change.to() + " 이 진행 중으로 남음";
            logs.add("schema: " + stuck + " — " + e.getMessage());
            throw new DeploymentFailedException(deployError.getMessage() + ". " + stuck, logs, e);
        }
    }

    private void route(DeployContext context, List<String> logs) {
        try {
            trafficRouter.route(context);
            recorder.record(context, logs, DeployStages.ROUTER,
                    "router: " + context.host() + " -> " + context.serviceName() + ":" + context.servicePort());
        } catch (RuntimeException e) {
            log.error("router module failed. app={} host={} message={}",
                    context.appName(), context.host(), e.getMessage(), e);
            logs.add("router: 적용 실패. service selector 는 이미 target 을 가리킬 수 있음");
            throw new DeploymentFailedException("router 적용 실패: " + context.host(), logs, e);
        }
    }

    private void watch(DeployContext context, List<String> logs) {
        try {
            deployMonitor.attached(context);
            recorder.record(context, logs, DeployStages.MONITOR,
                    "monitor: " + context.serviceName() + ":" + context.targetPort() + context.metricsPath());
        } catch (RuntimeException e) {
            log.warn("monitoring module failed. deploy continues. app={} message={}",
                    context.appName(), e.getMessage(), e);
            recorder.record(context, logs, DeployStages.MONITOR, "monitor: attach failed, deploy continues");
        }
    }

    private DeployCommand applyDefaults(DeployCommand command) {
        if (command == null) {
            return null;
        }
        String appName = firstNonBlank(command.appName(), properties.getAppName());
        if (appName.equals(command.appName())) {
            return command;
        }
        return new DeployCommand(
                appName,
                command.imageUrl(),
                command.targetPort(),
                command.namespace(),
                command.domain(),
                command.readinessPath(),
                command.livenessPath(),
                command.appVersion(),
                command.imagePullSecret(),
                command.extraEnv() == null ? Map.of() : command.extraEnv(),
                command.database(),
                command.host(),
                command.migrations(),
                command.canaryPath(),
                command.databaseEnv(),
                command.aliases());
    }

    private void validate(DeployCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command 가 비어 있다");
        }
        validateAppName(command.appName());
        if (command.imageUrl() == null || command.imageUrl().isBlank()) {
            throw new IllegalArgumentException("imageUrl 이 비어 있다");
        }
        if (command.targetPort() < 1 || command.targetPort() > 65535) {
            throw new IllegalArgumentException("targetPort 범위가 아니다: " + command.targetPort());
        }
        if (properties.getReadinessTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("readinessTimeoutSeconds 는 1 이상이어야 한다");
        }
    }

    static void validateAppName(String appName) {
        if (appName == null || !appName.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException(
                    "appName 은 소문자, 숫자, 하이픈만 사용할 수 있다: " + appName);
        }
        if (appName.length() > 55) {
            throw new IllegalArgumentException("appName 은 55자 이하여야 한다. '-ingress' 접미사를 붙이면 63자를 넘긴다");
        }
    }

    static String serviceName(String appName) {
        return appName + "-svc";
    }

    static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
