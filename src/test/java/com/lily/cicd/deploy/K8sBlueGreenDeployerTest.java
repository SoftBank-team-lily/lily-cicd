package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.module.TrafficRouter;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fabric8 {@link KubernetesServer} (CRUD mock) 로 색 전환만 검증한다.
 * 실제 클러스터의 컨트롤러가 없으므로, 성공 케이스에서는 Deployment status 를 Ready 로 채워 준다.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class K8sBlueGreenDeployerTest {

    private static final String NAMESPACE = "default";
    private static final String APP = "lily";
    private static final String IMAGE = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/lily-blog-sample:1.0.0";

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
    }

    @AfterEach
    void stopReadyMarker() {
        if (readyMarker != null) {
            readyMarker.shutdownNow();
        }
    }

    @Test
    void 서비스가_없으면_blue로_열고_green은_만들지_않는다() {
        startReadyMarker();

        DeploymentResultDto result = deployer(8).deploy(APP, IMAGE, 8080).join();

        assertEquals("SUCCESS", result.status());
        assertEquals("blue", result.activeColor());
        assertEquals("http://lily.domain.com", result.targetHostUrl());
        assertEquals("blue", serviceColor());
        assertNull(deployment("lily-green"));

        Deployment blue = deployment("lily-blue");
        assertEquals("blue", blue.getSpec().getTemplate().getMetadata().getLabels().get("color"));
        assertEquals("lily", blue.getSpec().getSelector().getMatchLabels().get("app"));
        assertEquals(IMAGE, blue.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals("/actuator/health/readiness",
                blue.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getHttpGet().getPath());
        assertEquals(5, blue.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds());
        assertEquals(3, blue.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getPeriodSeconds());
        assertEquals(8080, blue.getSpec().getTemplate().getSpec().getContainers().get(0)
                .getReadinessProbe().getHttpGet().getPort().getIntVal());
        assertEquals("blue", env(blue, "APP_COLOR"));
        assertEquals("8080", env(blue, "SERVER_PORT"));

        Service service = client.services().inNamespace(NAMESPACE).withName("lily-svc").get();
        assertEquals(80, service.getSpec().getPorts().get(0).getPort());
        assertEquals(8080, service.getSpec().getPorts().get(0).getTargetPort().getIntVal());

        Ingress ingress = client.network().v1().ingresses().inNamespace(NAMESPACE).withName("lily-ingress").get();
        assertEquals("nginx", ingress.getMetadata().getAnnotations().get("kubernetes.io/ingress.class"));
        assertEquals("lily.domain.com", ingress.getSpec().getRules().get(0).getHost());
        assertEquals("lily-svc",
                ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getBackend().getService().getName());
        assertEquals(80, ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0)
                .getBackend().getService().getPort().getNumber());
    }

    @Test
    void blue에서_green으로_전환하고_이전_슬롯은_0으로_줄인다() {
        startReadyMarker();
        givenService("blue");
        givenDeployment("blue", 1);

        DeploymentResultDto result = deployer(8).deploy(APP, IMAGE, 8080).join();

        assertEquals("green", result.activeColor());
        assertEquals("green", serviceColor());
        assertEquals(0, deployment("lily-blue").getSpec().getReplicas());
        assertEquals(1, deployment("lily-green").getSpec().getReplicas());
        assertEquals("green", env(deployment("lily-green"), "APP_COLOR"));
    }

    @Test
    void green에서_다시_blue로_돌아온다() {
        startReadyMarker();
        givenService("green");
        givenDeployment("green", 1);

        DeploymentResultDto result = deployer(8).deploy(APP, IMAGE, 8080).join();

        assertEquals("blue", result.activeColor());
        assertEquals("blue", serviceColor());
        assertEquals(0, deployment("lily-green").getSpec().getReplicas());
        assertEquals(1, deployment("lily-blue").getSpec().getReplicas());
    }

    @Test
    void readiness가_실패하면_target만_지우고_service는_그대로_둔다() {
        givenService("blue");
        givenDeployment("blue", 1);

        DeploymentFailedException error = assertThrows(DeploymentFailedException.class,
                () -> deployer(1).deploy(APP, IMAGE, 8080).join());

        assertTrue(error.getMessage().contains("Ready"));
        assertEquals("blue", serviceColor());
        assertNull(deployment("lily-green"));
        assertEquals(1, deployment("lily-blue").getSpec().getReplicas());
    }

    @Test
    void 첫_배포가_실패하면_service를_만들지_않는다() {
        DeploymentFailedException error = assertThrows(DeploymentFailedException.class,
                () -> deployer(1).deploy(APP, IMAGE, 8080).join());

        assertTrue(error.getLogs().stream().anyMatch(line -> line.contains("active service was not modified")));
        assertNull(client.services().inNamespace(NAMESPACE).withName("lily-svc").get());
        assertNull(deployment("lily-blue"));
    }

    @Test
    void 알_수_없는_color는_리소스를_만들기_전에_거절한다() {
        givenService("red");

        assertThrows(DeploymentFailedException.class, () -> deployer(8).deploy(APP, IMAGE, 8080).join());

        assertEquals("red", serviceColor());
        assertNull(deployment("lily-blue"));
        assertNull(deployment("lily-green"));
    }

    @Test
    void DB모듈이_준_환경변수는_컨테이너에_들어가고_요청값이_이긴다() {
        startReadyMarker();
        DatabaseProvisioner database = context -> Map.of(
                "DB_URL", "jdbc:postgresql://db/lily",
                "SPRING_PROFILES_ACTIVE", "prod");

        DeployCommand command = new DeployCommand(
                APP, IMAGE, 8080, null, null, null, null, "1.0.0", null,
                Map.of("SPRING_PROFILES_ACTIVE", "local"));
        deployer(8, database, new NginxIngressRouter(client), new Slf4jDeployLog(), new NoopDeployMonitor())
                .deploy(command)
                .join();

        Deployment blue = deployment("lily-blue");
        assertEquals("jdbc:postgresql://db/lily", env(blue, "DB_URL"));
        assertEquals("local", env(blue, "SPRING_PROFILES_ACTIVE"));
        assertEquals("blue", env(blue, "APP_COLOR"));
    }

    @Test
    void Router와_Monitoring은_구현_빈으로_갈아_끼운다() {
        startReadyMarker();
        AtomicReference<DeployContext> routed = new AtomicReference<>();
        AtomicReference<DeployContext> watched = new AtomicReference<>();
        TrafficRouter router = routed::set;
        DeployMonitor monitor = new DeployMonitor() {
            @Override
            public void attached(DeployContext context) {
                watched.set(context);
            }

            @Override
            public void failed(DeployContext context, String message) {
            }
        };

        deployer(8, new NoopDatabaseProvisioner(), router, new Slf4jDeployLog(), monitor)
                .deploy(APP, IMAGE, 8080)
                .join();

        assertEquals("lily", routed.get().appName());
        assertEquals("lily-svc", routed.get().serviceName());
        assertEquals(80, routed.get().servicePort());
        assertEquals("lily.domain.com", routed.get().host());
        assertEquals("blue", routed.get().targetColor());
        assertEquals("/actuator/prometheus", watched.get().metricsPath());
        assertEquals("blue", serviceColor());
        assertNull(client.network().v1().ingresses().inNamespace(NAMESPACE).withName("lily-ingress").get());
    }

    @Test
    void Monitoring이_실패해도_배포는_성공한다() {
        startReadyMarker();
        DeployMonitor monitor = new DeployMonitor() {
            @Override
            public void attached(DeployContext context) {
                throw new IllegalStateException("scrape down");
            }

            @Override
            public void failed(DeployContext context, String message) {
            }
        };

        DeploymentResultDto result = deployer(
                8, new NoopDatabaseProvisioner(), new NginxIngressRouter(client), new Slf4jDeployLog(), monitor)
                .deploy(APP, IMAGE, 8080)
                .join();

        assertEquals("SUCCESS", result.status());
        assertEquals("blue", serviceColor());
    }

    @Test
    void 다른_전략을_넣으면_블루그린_Deployment를_만들지_않는다() {
        DeploymentStrategy canary = new DeploymentStrategy() {
            @Override
            public String name() {
                return "canary";
            }

            @Override
            public SlotPlan plan(String namespace, String appName, java.util.List<String> logs) {
                logs.add("canary: plan");
                return new SlotPlan("canary", "stable");
            }

            @Override
            public void applyTarget(
                    DeployCommand command, String namespace, SlotPlan plan,
                    java.util.Map<String, String> databaseEnv, java.util.List<String> logs) {
                logs.add("canary: apply");
            }

            @Override
            public void awaitReady(String namespace, String appName, SlotPlan plan, java.util.List<String> logs) {
                logs.add("canary: ready");
            }

            @Override
            public void switchTraffic(DeployCommand command, String namespace, SlotPlan plan, java.util.List<String> logs) {
                logs.add("canary: switch");
            }

            @Override
            public void retirePrevious(String namespace, String appName, SlotPlan plan, java.util.List<String> logs) {
                logs.add("canary: retire");
            }
        };

        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(8);
        DeploymentResultDto result = new DeploymentEngine(
                properties,
                canary,
                new NoopDatabaseProvisioner(),
                new NginxIngressRouter(client),
                new Slf4jDeployLog(),
                new NoopDeployMonitor())
                .deploy(APP, IMAGE, 8080)
                .join();

        assertEquals("SUCCESS", result.status());
        assertEquals("canary", result.activeColor());
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("strategy=canary")));
        assertNull(deployment("lily-blue"));
        assertNull(deployment("lily-green"));
        assertNull(client.services().inNamespace(NAMESPACE).withName("lily-svc").get());
    }

    private DeploymentEngine deployer(long timeoutSeconds) {
        return deployer(timeoutSeconds,
                new NoopDatabaseProvisioner(),
                new NginxIngressRouter(client),
                new Slf4jDeployLog(),
                new NoopDeployMonitor());
    }

    private DeploymentEngine deployer(
            long timeoutSeconds,
            DatabaseProvisioner database,
            TrafficRouter router,
            DeployLog deployLog,
            DeployMonitor monitor) {
        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(timeoutSeconds);
        return new DeploymentEngine(
                properties,
                new BlueGreenDeploymentStrategy(client, properties),
                database,
                router,
                deployLog,
                monitor);
    }

    private void givenService(String color) {
        client.services().inNamespace(NAMESPACE).resource(new ServiceBuilder()
                .withNewMetadata().withName("lily-svc").withNamespace(NAMESPACE).endMetadata()
                .withNewSpec()
                    .withSelector(Map.of("app", APP, "color", color))
                    .addNewPort().withPort(80).withTargetPort(new IntOrString(8080)).endPort()
                .endSpec()
                .build()).create();
    }

    private void givenDeployment(String color, int replicas) {
        client.apps().deployments().inNamespace(NAMESPACE).resource(new DeploymentBuilder()
                .withNewMetadata()
                    .withName(APP + "-" + color)
                    .withNamespace(NAMESPACE)
                    .addToLabels("app", APP)
                    .addToLabels("color", color)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector()
                        .addToMatchLabels("app", APP)
                        .addToMatchLabels("color", color)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", APP)
                            .addToLabels("color", color)
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName(APP).withImage(IMAGE).endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build()).create();
    }

    /**
     * mock 에는 kube-controller 가 없어서 Deployment 가 스스로 Ready 가 되지 않는다.
     * 실제 클러스터에서 replica 가 뜨는 것을 status 로 대신한다.
     */
    private void startReadyMarker() {
        readyMarker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ready-marker");
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
            // 다음 주기에 다시 시도한다. 배포 스레드와 동시에 지워질 수 있다.
        }
    }

    private String serviceColor() {
        Service service = client.services().inNamespace(NAMESPACE).withName("lily-svc").get();
        return service.getSpec().getSelector().get("color");
    }

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NAMESPACE).withName(name).get();
    }

    private static String env(Deployment deployment, String name) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .filter(env -> name.equals(env.getName()))
                .map(EnvVar::getValue)
                .findFirst()
                .orElse(null);
    }
}
