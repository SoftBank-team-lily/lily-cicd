package com.lily.cicd.deploy;

import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.TrafficRouter;
import com.lily.cicd.release.DeployLock;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 앱을 클러스터에서 지운다. 배포가 만든 이름 규칙을 그대로 따른다.
 *
 * <ul>
 *   <li>라벨 {@code app}: Deployment {@code {app}-{slot}}, Service {@code {app}-svc}·{@code {app}-canary-svc},
 *       ConfigMap {@code {app}-{slot}-schema}·{@code lily-progress-{app}}·{@code lily-recovery-{app}}</li>
 *   <li>이름: Ingress {@code {app}-ingress}·{@code {app}-canary-ingress}, Secret {@code {app}-{slot}-db},
 *       PDB {@code {app}-{slot}-pdb}. Secret·PDB 는 list 권한이 없어서 슬롯 이름으로 지운다</li>
 * </ul>
 *
 * <p>배포·롤백과 같은 잠금을 잡는다. 진행 중이면 {@link com.lily.cicd.release.DeployConflictException}.
 * DB 는 {@code database=true} 일 때만 지운다 (DROP 이라 되돌릴 수 없다).
 */
public class AppRemover {

    /** blue-green 의 color 와 canary 의 track */
    private static final List<String> SLOTS = List.of("blue", "green", "stable", "canary");
    private static final Logger log = LoggerFactory.getLogger(AppRemover.class);

    private final KubernetesClient k8s;
    private final DeployLock lock;
    private final DeployProgress progress;
    private final DatabaseProvisioner databaseProvisioner;
    /** 트래픽 입구(라우트·canary Ingress)는 Router 모듈이 지운다 */
    private final TrafficRouter router;

    /** Ingress 를 직접 쓰는 기본 Router 로 */
    public AppRemover(KubernetesClient k8s, DeployLock lock, DeployProgress progress,
                      DatabaseProvisioner databaseProvisioner) {
        this(k8s, lock, progress, databaseProvisioner, new NginxIngressRouter(k8s));
    }

    public AppRemover(KubernetesClient k8s, DeployLock lock, DeployProgress progress,
                      DatabaseProvisioner databaseProvisioner, TrafficRouter router) {
        this.router = router;
        this.k8s = k8s;
        this.lock = lock;
        this.progress = progress;
        this.databaseProvisioner = databaseProvisioner;
    }

    /** @return 앱 리소스도 DB 도 없었으면 deleted 가 비고 database 가 "none" */
    public Removal remove(String namespace, String appName, boolean database) {
        try (DeployLock.Handle ignored = lock.acquire(namespace, appName, "delete")) {
            List<String> deleted = new ArrayList<>();
            // 트래픽 입구부터 끊는다
            deleted.addAll(router.remove(namespace, appName));
            for (HasMetadata d : k8s.apps().deployments().inNamespace(namespace).withLabel("app", appName).list().getItems()) {
                k8s.apps().deployments().inNamespace(namespace).withName(d.getMetadata().getName()).delete();
                deleted.add("deployment/" + d.getMetadata().getName());
            }
            for (HasMetadata s : k8s.services().inNamespace(namespace).withLabel("app", appName).list().getItems()) {
                k8s.services().inNamespace(namespace).withName(s.getMetadata().getName()).delete();
                deleted.add("service/" + s.getMetadata().getName());
            }
            for (String slot : SLOTS) {
                String pdb = appName + "-" + slot + "-pdb";
                if (!k8s.policy().v1().podDisruptionBudget().inNamespace(namespace).withName(pdb).delete().isEmpty()) {
                    deleted.add("pdb/" + pdb);
                }
                for (String secret : List.of(DatabaseSecret.name(appName + "-" + slot),
                        DatabaseSecret.savedName(appName + "-" + slot))) {
                    if (!k8s.secrets().inNamespace(namespace).withName(secret).delete().isEmpty()) {
                        deleted.add("secret/" + secret);
                    }
                }
            }
            for (HasMetadata c : k8s.configMaps().inNamespace(namespace).withLabel("app", appName).list().getItems()) {
                k8s.configMaps().inNamespace(namespace).withName(c.getMetadata().getName()).delete();
                deleted.add("configmap/" + c.getMetadata().getName());
            }
            progress.forget(namespace, appName);

            String db = "kept";
            if (database) {
                db = databaseProvisioner.release(appName) ? "deleted" : "none";
            }
            log.info("removed app={} namespace={} database={} resources={}", appName, namespace, db, deleted);
            return new Removal(appName, namespace, deleted, db);
        }
    }

    /** @param database deleted (지움), none (지우려 했지만 없음), kept (지우지 않음) */
    public record Removal(String appName, String namespace, List<String> deleted, String database) {

        public boolean nothing() {
            return deleted.isEmpty() && !"deleted".equals(database);
        }
    }
}
