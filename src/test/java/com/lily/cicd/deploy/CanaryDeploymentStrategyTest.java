package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.SchemaMigrator;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CanaryDeploymentStrategyTest {

    private static final String NAMESPACE = "default";
    private static final String APP = "lily";
    private static final String IMAGE = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/lily-blog-sample:1.0.0";
    private static final String IMAGE_V2 = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/lily-blog-sample:2.0.0";

    private static KubernetesServer server;

    private KubernetesClient client;
    private ScheduledExecutorService readyMarker;

    @BeforeAll
    static void startMockCluster() {
        server = new KubernetesServer(false, true);
        server.before();
    }

    @AfterAll
    static void stopMockCluster() {
        server.after();
    }

    @BeforeEach
    void resetCluster() {
        client = server.getClient();
        client.apps().deployments().inNamespace(NAMESPACE).delete();
        client.services().inNamespace(NAMESPACE).delete();
        client.network().v1().ingresses().inNamespace(NAMESPACE).delete();
        client.configMaps().inNamespace(NAMESPACE).delete();
        client.secrets().inNamespace(NAMESPACE).delete();
        client.leases().inNamespace(NAMESPACE).delete();
    }

    @AfterEach
    void stopReadyMarker() {
        if (readyMarker != null) {
            readyMarker.shutdownNow();
        }
    }

    @Test
    void 첫_배포는_stable만_만들고_canary는_없다() {
        startReadyMarker();

        DeploymentResultDto result = engine(8, 20).deploy(APP, IMAGE, 8080).join();

        assertEquals("SUCCESS", result.status());
        assertEquals("stable", result.activeColor());
        assertEquals("http://lily.domain.com", result.targetHostUrl());
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("strategy=canary")));
        assertNull(deployment("lily-canary"));

        Deployment stable = deployment("lily-stable");
        assertEquals(2, stable.getSpec().getReplicas());
        assertEquals("stable", stable.getSpec().getTemplate().getMetadata().getLabels().get("track"));
        assertEquals(IMAGE, stable.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals("stable", env(stable, "APP_COLOR"));
        assertEquals("/actuator/health/readiness",
                stable.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getHttpGet().getPath());

        Service service = client.services().inNamespace(NAMESPACE).withName("lily-svc").get();
        assertEquals(Map.of("app", APP, "track", "stable"), service.getSpec().getSelector());
        assertEquals(80, service.getSpec().getPorts().get(0).getPort());
        assertEquals(8080, service.getSpec().getPorts().get(0).getTargetPort().getIntVal());
    }

    @Test
    void 다음_배포는_비율을_100까지_올린_뒤_이전_슬롯을_0으로_남긴다() {
        startReadyMarker();
        engine(8, 20).deploy(APP, IMAGE, 8080).join();

        DeploymentResultDto result = engine(8, 20).deploy(APP, IMAGE_V2, 8080).join();

        assertEquals("SUCCESS", result.status());
        assertEquals("canary", result.activeColor());

        Deployment canary = deployment("lily-canary");
        assertEquals(IMAGE_V2, canary.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(2, canary.getSpec().getReplicas());
        assertEquals("canary", env(canary, "APP_COLOR"));

        Deployment stable = deployment("lily-stable");
        assertEquals(IMAGE, stable.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(0, stable.getSpec().getReplicas());
        assertEquals(Map.of("app", APP, "track", "canary"),
                client.services().inNamespace(NAMESPACE).withName("lily-svc").get().getSpec().getSelector());
        assertNull(client.network().v1().ingresses().inNamespace(NAMESPACE).withName("lily-canary-ingress").get());
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=0 stable=2 canary=2")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=20 stable=2 canary=2")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=100 stable=2 canary=2")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("shifted service lily-svc track=canary")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("retired lily-stable replicas=0")));
    }

    @Test
    void 가중치_1퍼센트는_0에서_1칸씩_100까지_올린다() {
        startReadyMarker();
        engine(8, 1).deploy(APP, IMAGE, 8080).join();
        DeploymentResultDto result = engine(8, 1).deploy(APP, IMAGE_V2, 8080).join();

        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=0 ")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=1 ")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=100 ")));
        assertEquals(2, deployment("lily-canary").getSpec().getReplicas());
        assertEquals(0, deployment("lily-stable").getSpec().getReplicas());
        assertEquals(IMAGE_V2, deployment("lily-canary").getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    }

    @Test
    void 가중치_40퍼센트는_40칸으로_100까지_올린다() {
        startReadyMarker();
        engine(8, 40).deploy(APP, IMAGE, 8080).join();
        DeploymentResultDto result = engine(8, 40).deploy(APP, IMAGE_V2, 8080).join();

        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=40 ")));
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("canary: weight=80 ")));
        assertTrue(result.logs().stream().noneMatch(line -> line.contains("canary: weight=20 ")));
        assertEquals(2, deployment("lily-canary").getSpec().getReplicas());
        assertEquals(0, deployment("lily-stable").getSpec().getReplicas());
        assertEquals(IMAGE_V2, deployment("lily-canary").getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    }

    @Test
    void readiness가_실패하면_canary만_지우고_stable은_그대로_둔다() {
        startReadyMarker();
        engine(8, 20).deploy(APP, IMAGE, 8080).join();
        readyMarker.shutdownNow();
        readyMarker = null;

        DeploymentFailedException error = assertThrows(DeploymentFailedException.class,
                () -> engine(1, 20).deploy(APP, IMAGE_V2, 8080).join());

        assertTrue(error.getLogs().stream().anyMatch(line -> line.contains("active service was not modified")));
        assertNull(deployment("lily-canary"));
        assertEquals(2, deployment("lily-stable").getSpec().getReplicas());
        assertEquals(IMAGE, deployment("lily-stable").getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(Map.of("app", APP, "track", "stable"),
                client.services().inNamespace(NAMESPACE).withName("lily-svc").get().getSpec().getSelector());
        assertNull(client.network().v1().ingresses().inNamespace(NAMESPACE).withName("lily-canary-ingress").get());
    }

    @Test
    void 첫_배포가_실패하면_service를_만들지_않는다() {
        DeploymentFailedException error = assertThrows(DeploymentFailedException.class,
                () -> engine(1, 20).deploy(APP, IMAGE, 8080).join());

        assertTrue(error.getLogs().stream().anyMatch(line -> line.contains("active service was not modified")));
        assertNull(client.services().inNamespace(NAMESPACE).withName("lily-svc").get());
        assertNull(deployment("lily-stable"));
        assertNull(deployment("lily-canary"));
    }

    @Test
    void 가중치가_범위를_벗어나면_리소스를_만들기_전에_거절한다() {
        for (int weight : new int[] {0, 51}) {
            DeploymentFailedException error = assertThrows(DeploymentFailedException.class,
                    () -> engine(8, weight).deploy(APP, IMAGE, 8080).join());
            assertTrue(error.getMessage().contains("canary weight"));
        }
        assertNull(deployment("lily-stable"));
        assertNull(deployment("lily-canary"));
        assertNull(client.services().inNamespace(NAMESPACE).withName("lily-svc").get());
    }

    @Test
    void 세_번째_배포는_쉬는_stable에_올리고_canary를_0으로_남긴다() {
        startReadyMarker();
        engine(8, 20).deploy(APP, IMAGE, 8080).join();
        engine(8, 20).deploy(APP, IMAGE_V2, 8080).join();

        String imageV3 = IMAGE + "-v3";
        DeploymentResultDto result = engine(8, 20).deploy(APP, imageV3, 8080).join();

        assertEquals("SUCCESS", result.status());
        assertEquals("stable", result.activeColor());
        assertEquals(imageV3, deployment("lily-stable").getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(2, deployment("lily-stable").getSpec().getReplicas());
        assertEquals(IMAGE_V2, deployment("lily-canary").getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(0, deployment("lily-canary").getSpec().getReplicas());
        assertEquals(Map.of("app", APP, "track", "stable"),
                client.services().inNamespace(NAMESPACE).withName("lily-svc").get().getSpec().getSelector());
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("retired lily-canary replicas=0")));
    }

    @Test
    void DB_환경변수는_새_슬롯에_남고_요청값이_이긴다() {
        startReadyMarker();
        DatabaseProvisioner database = context -> Map.of(
                "DB_URL", "jdbc:postgresql://db/lily",
                "SPRING_PROFILES_ACTIVE", "prod");
        engine(8, 20, database).deploy(APP, IMAGE, 8080).join();

        DeployCommand command = new DeployCommand(
                APP, IMAGE_V2, 8080, null, null, null, null, "2.0.0", "ecr",
                Map.of("SPRING_PROFILES_ACTIVE", "local"));
        engine(8, 20, database).deploy(command).join();

        Deployment canary = deployment("lily-canary");
        assertEquals(0, deployment("lily-stable").getSpec().getReplicas());
        assertEquals("jdbc:postgresql://db/lily", env(canary, "DB_URL"));
        assertEquals("local", env(canary, "SPRING_PROFILES_ACTIVE"));
        assertEquals("canary", env(canary, "APP_COLOR"));
        assertEquals("2.0.0", env(canary, "APP_VERSION"));
        assertEquals("8080", env(canary, "SERVER_PORT"));
        assertEquals("ecr", canary.getSpec().getTemplate().getSpec().getImagePullSecrets().get(0).getName());
    }

    private DeploymentEngine engine(long timeoutSeconds, int weightPercent) {
        return engine(timeoutSeconds, weightPercent, new NoopDatabaseProvisioner());
    }

    private DeploymentEngine engine(long timeoutSeconds, int weightPercent, DatabaseProvisioner database) {
        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(timeoutSeconds);
        properties.setCanaryWeightPercent(weightPercent);
        properties.setCanaryStepSeconds(0);
        properties.setStrategy("canary");
        return new DeploymentEngine(
                properties,
                new CanaryDeploymentStrategy(client, properties),
                database,
                new NginxIngressRouter(client),
                new Slf4jDeployLog(),
                new NoopDeployMonitor(),
                new SchemaMigrator(),
                new ReleaseStore(client),
                new DeployLock(client));
    }

    private void startReadyMarker() {
        readyMarker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "canary-ready-marker");
            thread.setDaemon(true);
            return thread;
        });
        readyMarker.scheduleAtFixedRate(this::markDeploymentsReady, 30, 50, TimeUnit.MILLISECONDS);
    }

    private void markDeploymentsReady() {
        try {
            for (Deployment deployment : client.apps().deployments().inNamespace(NAMESPACE).list().getItems()) {
                Integer desired = deployment.getSpec().getReplicas();
                if (desired == null || desired == 0) {
                    continue;
                }
                Integer available = deployment.getStatus() == null ? null : deployment.getStatus().getAvailableReplicas();
                if (available != null && available >= desired) {
                    continue;
                }
                client.apps().deployments()
                        .inNamespace(NAMESPACE)
                        .withName(deployment.getMetadata().getName())
                        .editStatus(current -> new DeploymentBuilder(current)
                                .withNewStatus()
                                    .withReplicas(desired)
                                    .withAvailableReplicas(desired)
                                    .withReadyReplicas(desired)
                                .endStatus()
                                .build());
            }
        } catch (Exception ignored) {
            // 다음 주기에 다시 시도한다.
        }
    }

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NAMESPACE).withName(name).get();
    }

    private String env(Deployment deployment, String name) {
        return ContainerEnv.value(client, deployment, name);
    }
}
