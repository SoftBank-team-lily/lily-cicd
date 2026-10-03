package com.lily.cicd.deploy;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.release.ReleaseStore.Release;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 롤백 창이 지난 pgroll 마이그레이션을 complete 한다 (옛 컬럼·이전 버전 스키마 삭제).
 * 다음 배포가 먼저 오면 그 배포가 시작할 때 complete 하므로, 여기서는 창이 지난 것만 본다.
 * 같은 앱의 배포·롤백과 겹치지 않게 {@link DeployLock} 을 잡는다. 잡지 못하면 다음 회차에 다시 본다.
 */
public class PgrollCompleter {

    private static final Logger log = LoggerFactory.getLogger(PgrollCompleter.class);

    private final PgrollSchema pgroll;
    private final DatabaseProvisioner databaseProvisioner;
    private final ReleaseStore releaseStore;
    private final DeployLock deployLock;
    private final Duration interval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public PgrollCompleter(PgrollSchema pgroll, DatabaseProvisioner databaseProvisioner, ReleaseStore releaseStore,
                           DeployLock deployLock, Duration interval) {
        this.pgroll = pgroll;
        this.databaseProvisioner = databaseProvisioner;
        this.releaseStore = releaseStore;
        this.deployLock = deployLock;
        this.interval = interval;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("pgroll-completer").start(() -> {
            while (!stopped.get()) {
                completeDue();
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        stopped.set(true);
    }

    /** 롤백 창이 지난 active 마이그레이션을 모두 complete 한다 */
    public void completeDue() {
        try {
            Instant now = Instant.now();
            for (Deployment d : releaseStore.pgrollSlots()) {
                Release release = ReleaseStore.toRelease(d);
                if (PgrollSchema.ACTIVE.equals(release.pgrollState()) && release.pgrollCompleteAfter() != null
                        && now.isAfter(release.pgrollCompleteAfter())) {
                    String app = d.getMetadata().getLabels().get("app");
                    try {
                        complete(d.getMetadata().getNamespace(), app, release);
                    } catch (DeployConflictException e) {
                        log.info("pgroll complete deferred, app busy. app={}", app);
                    } catch (RuntimeException e) {
                        log.error("pgroll complete failed. app={} migration={} message={}",
                                app, release.schemaVersion(), e.getMessage(), e);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("pgroll completer sweep failed. message={}", e.getMessage(), e);
        }
    }

    /**
     * 롤백 창을 기다리지 않고 지금 complete 한다.
     *
     * @throws DeployConflictException 진행 중인 마이그레이션이 없거나 다른 작업이 진행 중
     */
    public List<String> completeNow(String namespace, String appName) {
        Release release = activeRelease(namespace, appName)
                .orElseThrow(() -> new DeployConflictException(appName + " 에 롤백 창이 열린 pgroll 마이그레이션이 없다"));
        return complete(namespace, appName, release);
    }

    private List<String> complete(String namespace, String appName, Release release) {
        List<String> logs = new ArrayList<>();
        try (DeployLock.Handle lock = deployLock.acquire(namespace, appName, "pgroll-complete")) {
            // 잠금을 잡는 사이에 롤백이나 다음 배포가 상태를 바꿨을 수 있어서 다시 읽는다
            Release now = releaseStore.read(namespace, appName, release.slot()).orElse(release);
            if (!PgrollSchema.ACTIVE.equals(now.pgrollState())) {
                logs.add("schema: pgroll " + release.schemaVersion() + " 은 이미 " + now.pgrollState());
                return logs;
            }
            DeployContext context = new DeployContext(appName, namespace, null, 0, DeploymentEngine.SERVICE_PORT,
                    appName + ".complete", "pgroll-complete", release.slot(), DeploymentEngine.serviceName(appName),
                    DeploymentEngine.METRICS_PATH, release.database());
            Map<String, String> env = databaseProvisioner.prepare(context);
            Optional<String> active = pgroll.active(env);
            if (active.filter(release.schemaVersion()::equals).isPresent()) {
                pgroll.complete(env, namespace, appName, release.schemaVersion(), logs);
            } else {
                // 다음 배포가 먼저 complete 했다
                pgroll.markState(namespace, appName, release.schemaVersion(), PgrollSchema.COMPLETE);
                logs.add("schema: pgroll " + release.schemaVersion() + " 은 이미 complete 됨");
            }
            log.info("pgroll completed. app={} {}", appName, logs);
            return logs;
        }
    }

    private Optional<Release> activeRelease(String namespace, String appName) {
        return releaseStore.pgrollSlots().stream()
                .filter(d -> namespace.equals(d.getMetadata().getNamespace()))
                .filter(d -> d.getMetadata().getLabels() != null
                        && appName.equals(d.getMetadata().getLabels().get("app")))
                .map(ReleaseStore::toRelease)
                .filter(r -> PgrollSchema.ACTIVE.equals(r.pgrollState()))
                .findFirst();
    }
}
