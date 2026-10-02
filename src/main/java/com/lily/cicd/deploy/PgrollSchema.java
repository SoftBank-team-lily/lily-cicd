package com.lily.cicd.deploy;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.release.ReleaseStore.Release;
import com.lily.cicd.schema.PgrollEnv;
import com.lily.cicd.schema.PgrollMigrator;
import com.lily.cicd.schema.PgrollMigrator.PgrollChange;
import com.lily.cicd.schema.PgrollSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 배포·롤백·복구가 함께 쓰는 pgroll 단계. 순서와 규칙은 docs/schema-migration.md 7 절.
 *
 * <p>릴리스의 pgroll 상태는 슬롯 Deployment 어노테이션에 남긴다.
 * active 인 동안이 롤백 창이고, 창이 지나면 {@link PgrollCompleter} 가 complete 한다.
 */
public class PgrollSchema {

    static final String ACTIVE = "active";
    static final String COMPLETE = "complete";
    static final String ROLLED_BACK = "rolled-back";
    private static final Logger log = LoggerFactory.getLogger(PgrollSchema.class);

    private final PgrollMigrator migrator;
    private final DatabaseProvisioner databaseProvisioner;
    private final ReleaseStore releaseStore;
    private final KubernetesClient k8s;
    private final Duration rollbackWindow;

    public PgrollSchema(PgrollMigrator migrator, DatabaseProvisioner databaseProvisioner, ReleaseStore releaseStore,
                        KubernetesClient k8s, Duration rollbackWindow) {
        this.migrator = migrator;
        this.databaseProvisioner = databaseProvisioner;
        this.releaseStore = releaseStore;
        this.k8s = k8s;
        this.rollbackWindow = rollbackWindow;
    }

    /** 앱 DB 에 pgroll 을 켜고(DB 모듈이 관리자 권한으로) 이번 커밋의 마이그레이션을 시작한다 */
    PgrollChange migrate(DeployContext context, Map<String, String> databaseEnv, PgrollSet set,
                         boolean oldVersionLive, List<String> logs) {
        databaseProvisioner.enablePgroll(context);
        PgrollChange change = migrator.migrate(databaseEnv, set, oldVersionLive, logs);
        if (change.completed() != null) {
            markState(context.namespace(), context.appName(), change.completed(), COMPLETE);
        }
        return change;
    }

    /** 마이그레이션 없이 배포되는 커밋도 최신 버전 스키마로 접속하게 한다. pgroll 을 쓰지 않는 DB 면 빈 값 */
    Optional<String> latest(Map<String, String> databaseEnv) {
        try {
            return migrator.latest(databaseEnv);
        } catch (RuntimeException e) {
            log.warn("pgroll state lookup failed, app keeps default schema. message={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 슬롯 Secret 에 넣을 접속 정보. 버전 스키마로 붙고, 앱 자체 Flyway 는 끈다 */
    static Map<String, String> appEnv(Map<String, String> databaseEnv, String migration) {
        Map<String, String> env = new LinkedHashMap<>(PgrollEnv.withSchema(databaseEnv,
                PgrollSet.versionSchema(migration)));
        env.put(DeploymentEngine.APP_FLYWAY_ENV, "false");
        return env;
    }

    /** 트래픽을 새 슬롯으로 옮긴 뒤. 여기부터 롤백 창이다 */
    void opened(String namespace, String appName, String slot, List<String> logs) {
        Instant until = Instant.now().plus(rollbackWindow);
        releaseStore.markPgroll(namespace, appName, slot, ACTIVE, until);
        logs.add("schema: pgroll 롤백 창 " + rollbackWindow.toSeconds() + "s (until " + until + ")");
    }

    void rollback(Map<String, String> databaseEnv, String migration, List<String> logs) {
        migrator.rollback(databaseEnv, migration, logs);
    }

    Optional<String> active(Map<String, String> databaseEnv) {
        return migrator.active(databaseEnv);
    }

    void complete(Map<String, String> databaseEnv, String namespace, String appName, String migration,
                  List<String> logs) {
        migrator.complete(databaseEnv, migration, logs);
        markState(namespace, appName, migration, COMPLETE);
    }

    void markState(String namespace, String appName, String migration, String state) {
        for (Deployment d : releaseStore.pgrollSlots()) {
            Release release = ReleaseStore.toRelease(d);
            if (namespace.equals(d.getMetadata().getNamespace())
                    && appName.equals(label(d, "app"))
                    && migration.equals(release.schemaVersion())
                    && release.pgrollState() != null) {
                releaseStore.markPgroll(namespace, appName, release.slot(), state, null);
            }
        }
    }

    /**
     * replica 를 0 으로 만든 슬롯의 Pod 가 모두 사라질 때까지 기다린다.
     * 버전 스키마를 지우기 전에 그 스키마를 쓰는 Pod 가 남아 있으면 처리 중이던 요청이 실패한다.
     */
    void awaitNoPods(String namespace, String deploymentName, Duration timeout, List<String> logs) {
        Deployment d = k8s.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
        if (d == null || d.getSpec().getSelector() == null) {
            return;
        }
        Map<String, String> selector = d.getSpec().getSelector().getMatchLabels();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            int left = k8s.pods().inNamespace(namespace).withLabels(selector).list().getItems().size();
            if (left == 0) {
                logs.add("schema: " + deploymentName + " Pod 종료 확인");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logs.add("schema: " + deploymentName + " Pod 가 " + timeout.toSeconds() + "s 안에 다 내려가지 않음, 계속 진행");
    }

    Duration rollbackWindow() {
        return rollbackWindow;
    }

    private static String label(Deployment d, String key) {
        return d.getMetadata().getLabels() == null ? null : d.getMetadata().getLabels().get(key);
    }
}
