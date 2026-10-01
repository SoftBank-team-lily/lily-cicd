package com.lily.cicd.deploy;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployStages;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.MigrationSet;
import com.lily.cicd.schema.SchemaMigrator;
import com.lily.cicd.schema.SchemaVersions;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 배포 스레드가 죽으면 {@code finally} 가 실행되지 않는다.
 * 스키마를 적용한 뒤 트래픽을 옮기기 전에 죽으면 이전 슬롯이 새 스키마 위에 남고,
 * canary Service 가 남으면 다음 판정과 섞인다.
 *
 * <p>진행 시각이 {@link #STALE} 보다 오래 멈추면 이 프로세스가 정리한다.
 * 살아 있는 배포는 몇 초마다 시각을 갱신하므로 건드리지 않는다.
 * 트래픽이 이미 새 슬롯이면 스키마는 되돌리지 않는다.
 */
public class DeployRecovery {

    /** 진행 맥박(5초)이 이 시간 동안 없으면 배포 스레드가 죽은 것으로 본다 */
    static final Duration STALE = Duration.ofSeconds(20);
    private static final Logger log = LoggerFactory.getLogger(DeployRecovery.class);
    private static final String CANARY_INGRESS = "-canary-ingress";
    private static final String CANARY_SERVICE = "-canary-svc";

    private final KubernetesClient k8s;
    private final SchemaMigrator schemaMigrator;
    private final DatabaseProvisioner databaseProvisioner;
    private final ReleaseStore releaseStore;
    private final DeployProgress progress;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public DeployRecovery(KubernetesClient k8s, SchemaMigrator schemaMigrator, DatabaseProvisioner databaseProvisioner,
                          ReleaseStore releaseStore, DeployProgress progress) {
        this.k8s = k8s;
        this.schemaMigrator = schemaMigrator;
        this.databaseProvisioner = databaseProvisioner;
        this.releaseStore = releaseStore;
        this.progress = progress;
    }

    /** 프로세스가 켜져 있는 동안 멈춘 배포를 정리한다 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("deploy-recovery").start(() -> {
            while (!stopped.get()) {
                recover();
                try {
                    Thread.sleep(10_000);
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

    /** 한 번 훑는다. 테스트와 기동 직후에 쓴다 */
    public void recover() {
        if (k8s == null) {
            return;
        }
        try {
            sweepCanaries();
            for (ConfigMap map : k8s.configMaps().inAnyNamespace().withLabel("lily.io/recovery", "true").list().getItems()) {
                recoverOne(map);
            }
        } catch (RuntimeException e) {
            log.error("deploy recovery failed. message={}", e.getMessage(), e);
        }
    }

    /**
     * 스키마를 적용한 직후, 트래픽을 옮기기 전에 남긴다.
     * 스크립트 ConfigMap 이 먼저 있어야 되돌릴 U 가 있다.
     */
    public void arm(String namespace, String appName, String targetSlot, String schemaFrom, String database) {
        if (k8s == null) {
            return;
        }
        try {
            k8s.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                    .withNewMetadata()
                        .withName(name(appName))
                        .withNamespace(namespace)
                        .addToLabels("app", appName)
                        .addToLabels("lily.io/recovery", "true")
                    .endMetadata()
                    .addToData("targetSlot", targetSlot)
                    .addToData("schemaFrom", schemaFrom == null ? "" : schemaFrom)
                    .addToData("database", database == null ? "" : database)
                    .build()).createOrReplace();
        } catch (RuntimeException e) {
            log.warn("recovery arm failed. app={} message={}", appName, e.getMessage());
        }
    }

    /** 트래픽이 새 슬롯으로 옮겨진 뒤. 이 시점의 스키마는 되돌리면 안 된다 */
    public void clear(String namespace, String appName) {
        if (k8s == null) {
            return;
        }
        try {
            k8s.configMaps().inNamespace(namespace).withName(name(appName)).delete();
        } catch (RuntimeException e) {
            log.warn("recovery clear failed. app={} message={}", appName, e.getMessage());
        }
    }

    private void sweepCanaries() {
        k8s.network().v1().ingresses().inAnyNamespace().list().getItems().forEach(ingress -> {
            String app = strip(ingress.getMetadata().getName(), CANARY_INGRESS);
            if (app != null && !live(ingress.getMetadata().getNamespace(), app)) {
                deleteCanary(ingress.getMetadata().getNamespace(), app);
            }
        });
        k8s.services().inAnyNamespace().list().getItems().forEach(service -> {
            String app = strip(service.getMetadata().getName(), CANARY_SERVICE);
            if (app != null && !live(service.getMetadata().getNamespace(), app)) {
                deleteCanary(service.getMetadata().getNamespace(), app);
            }
        });
    }

    private void recoverOne(ConfigMap map) {
        String namespace = map.getMetadata().getNamespace();
        String app = map.getMetadata().getLabels().get("app");
        if (app == null || live(namespace, app)) {
            return;
        }
        Map<String, String> data = map.getData() == null ? Map.of() : map.getData();
        String target = data.get("targetSlot");
        String schemaFrom = data.get("schemaFrom");
        String database = data.get("database");
        deleteCanary(namespace, app);

        String message = "배포 중 프로세스가 죽어 중단됨";
        if (target != null && schemaFrom != null && !schemaFrom.isBlank()
                && !target.equals(serviceColor(namespace, app))) {
            List<String> logs = new ArrayList<>();
            try {
                DeployContext context = new DeployContext(app, namespace, null, 0, DeploymentEngine.SERVICE_PORT,
                        app + ".recovery", "recovery", target, DeploymentEngine.serviceName(app),
                        DeploymentEngine.METRICS_PATH, database);
                Map<String, String> env = databaseProvisioner.prepare(context);
                MigrationSet scripts = releaseStore.loadScripts(namespace, app, target);
                schemaMigrator.rollback(env, scripts, SchemaVersions.parse(schemaFrom), false, logs);
                message = "배포 중 프로세스가 죽어 스키마를 " + schemaFrom + " 으로 되돌림";
                log.warn("recovered schema. app={} {}", app, logs);
            } catch (RuntimeException e) {
                message = "배포 중 프로세스가 죽었고 스키마를 되돌리지 못함: " + e.getMessage();
                log.error("schema recovery failed. app={} message={}", app, e.getMessage(), e);
            }
        }
        progress.update(namespace, app, DeployStages.FAILED, message, null);
        clear(namespace, app);
    }

    private boolean live(String namespace, String app) {
        return progress.get(namespace, app)
                .filter(snapshot -> !DeployStages.FAILED.equals(snapshot.stage())
                        && !DeployStages.SUCCEEDED.equals(snapshot.stage()))
                .filter(snapshot -> snapshot.updatedAt().plus(STALE).isAfter(Instant.now()))
                .isPresent();
    }

    private void deleteCanary(String namespace, String app) {
        try {
            k8s.network().v1().ingresses().inNamespace(namespace).withName(app + CANARY_INGRESS).delete();
            k8s.services().inNamespace(namespace).withName(app + CANARY_SERVICE).delete();
        } catch (RuntimeException e) {
            log.warn("canary sweep failed. app={} message={}", app, e.getMessage());
        }
    }

    private String serviceColor(String namespace, String app) {
        Service service = k8s.services().inNamespace(namespace).withName(DeploymentEngine.serviceName(app)).get();
        if (service == null || service.getSpec() == null || service.getSpec().getSelector() == null) {
            return null;
        }
        return service.getSpec().getSelector().get("color");
    }

    private static String strip(String name, String suffix) {
        if (name == null || !name.endsWith(suffix) || name.length() == suffix.length()) {
            return null;
        }
        return name.substring(0, name.length() - suffix.length());
    }

    static String name(String appName) {
        return "lily-recovery-" + appName;
    }
}
