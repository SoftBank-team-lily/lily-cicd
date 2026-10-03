package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppRemoverTest {

    private static final String NS = "default";
    private static KubernetesServer server;
    private KubernetesClient client;
    private final List<String> released = new ArrayList<>();
    private final DatabaseProvisioner provisioner = new DatabaseProvisioner() {
        @Override
        public Map<String, String> prepare(DeployContext context) {
            return Map.of();
        }

        @Override
        public boolean release(String appName) {
            released.add(appName);
            return true;
        }
    };

    @BeforeAll
    static void start() {
        server = new KubernetesServer(false, true);
        server.before();
    }

    @AfterAll
    static void stop() {
        server.after();
    }

    @BeforeEach
    void reset() {
        client = server.getClient();
        client.apps().deployments().inNamespace(NS).delete();
        client.services().inNamespace(NS).delete();
        client.configMaps().inNamespace(NS).delete();
        client.secrets().inNamespace(NS).delete();
        client.network().v1().ingresses().inNamespace(NS).delete();
        client.leases().inNamespace(NS).delete();
        released.clear();
    }

    @Test
    void 앱_리소스를_모두_지우고_다른_앱은_남긴다() {
        givenApp("blog");
        givenApp("shop");
        AppRemover remover = new AppRemover(client, new DeployLock(client), new DeployProgress(), provisioner);

        AppRemover.Removal removal = remover.remove(NS, "blog", false);

        assertEquals("kept", removal.database());
        assertTrue(released.isEmpty());
        assertNull(client.apps().deployments().inNamespace(NS).withName("blog-blue").get());
        assertNull(client.apps().deployments().inNamespace(NS).withName("blog-green").get());
        assertNull(client.services().inNamespace(NS).withName("blog-svc").get());
        assertNull(client.network().v1().ingresses().inNamespace(NS).withName("blog-ingress").get());
        assertNull(client.secrets().inNamespace(NS).withName("blog-blue-db").get());
        assertNull(client.configMaps().inNamespace(NS).withName("lily-progress-blog").get());
        assertNull(client.leases().inNamespace(NS).withName("lily-lock-blog").get());
        assertNotNull(client.apps().deployments().inNamespace(NS).withName("shop-blue").get());
        assertNotNull(client.services().inNamespace(NS).withName("shop-svc").get());
        assertNotNull(client.secrets().inNamespace(NS).withName("shop-blue-db").get());
    }

    @Test
    void database를_주면_DB도_지운다() {
        givenApp("blog");
        AppRemover remover = new AppRemover(client, new DeployLock(client), new DeployProgress(), provisioner);

        AppRemover.Removal removal = remover.remove(NS, "blog", true);

        assertEquals("deleted", removal.database());
        assertEquals(List.of("blog"), released);
    }

    @Test
    void 없는_앱이면_아무것도_지우지_않는다() {
        AppRemover remover = new AppRemover(client, new DeployLock(client), new DeployProgress(),
                new DatabaseProvisioner() {
                    @Override
                    public Map<String, String> prepare(DeployContext context) {
                        return Map.of();
                    }
                });

        assertTrue(remover.remove(NS, "ghost", true).nothing());
    }

    @Test
    void 배포_중이면_지우지_않는다() {
        givenApp("blog");
        DeployLock lock = new DeployLock(client);
        AppRemover remover = new AppRemover(client, lock, new DeployProgress(), provisioner);

        try (DeployLock.Handle ignored = lock.acquire(NS, "blog", "deploy")) {
            assertThrows(DeployConflictException.class, () -> remover.remove(NS, "blog", true));
        }
        assertNotNull(client.apps().deployments().inNamespace(NS).withName("blog-blue").get());
        assertTrue(released.isEmpty());
    }

    @Test
    void 중지하면_모든_슬롯이_0이고_시작하면_활성_슬롯만_기본값() {
        givenApp("blog");
        DeployProperties properties = new DeployProperties();
        DeployLock lock = new DeployLock(client);
        AppController controller = new AppController(client, properties, lock,
                new AppRemover(client, lock, new DeployProgress(), provisioner));

        controller.stop("blog", null);
        assertEquals(0, replicas("blog-blue"));
        assertEquals(0, replicas("blog-green"));

        controller.start("blog", null);
        assertEquals(properties.getReplicas(), replicas("blog-blue"));
        assertEquals(0, replicas("blog-green"));
    }

    @Test
    void canary_전략_앱에_레플리카_0을_보내면_track이_가리키는_canary_슬롯을_0으로_줄인다() {
        givenCanaryApp("shop");
        DeployLock lock = new DeployLock(client);
        AppController controller = new AppController(client, new DeployProperties(), lock,
                new AppRemover(client, lock, new DeployProgress(), provisioner));

        var response = controller.scale("shop", null, new AppController.ScaleRequest(0));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("canary", response.getBody().activeColor());
        assertEquals(0, replicas("shop-canary"));
        assertEquals(0, replicas("shop-stable"));
    }

    @Test
    void canary_전략_앱을_조회하면_track이_가리키는_canary_슬롯의_레플리카를_돌려준다() {
        givenCanaryApp("shop");
        DeployLock lock = new DeployLock(client);
        AppController controller = new AppController(client, new DeployProperties(), lock,
                new AppRemover(client, lock, new DeployProgress(), provisioner));

        var response = controller.status("shop", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("canary", response.getBody().activeColor());
        assertEquals(2, response.getBody().replicas());
    }

    private int replicas(String name) {
        return client.apps().deployments().inNamespace(NS).withName(name).get().getSpec().getReplicas();
    }

    /** canary 전략으로 두 번 배포한 상태: 트래픽은 canary(2), stable 은 0 으로 쉰다 */
    private void givenCanaryApp(String app) {
        for (String track : List.of("stable", "canary")) {
            client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                    .withNewMetadata().withName(app + "-" + track).withNamespace(NS)
                        .addToLabels("app", app).addToLabels("track", track).endMetadata()
                    .withNewSpec().withReplicas("canary".equals(track) ? 2 : 0)
                        .withNewSelector().addToMatchLabels("app", app).addToMatchLabels("track", track).endSelector()
                        .withNewTemplate().withNewMetadata().addToLabels("app", app).addToLabels("track", track).endMetadata()
                            .withNewSpec().addNewContainer().withName(app).withImage("nginx").endContainer().endSpec()
                        .endTemplate()
                    .endSpec()
                    .build()).create();
        }
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withNewMetadata().withName(app + "-svc").withNamespace(NS).addToLabels("app", app).endMetadata()
                .withNewSpec().addToSelector("app", app).addToSelector("track", "canary").endSpec()
                .build()).create();
    }

    private void givenApp(String app) {
        for (String color : List.of("blue", "green")) {
            client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                    .withNewMetadata().withName(app + "-" + color).withNamespace(NS)
                        .addToLabels("app", app).addToLabels("color", color).endMetadata()
                    .withNewSpec().withReplicas("blue".equals(color) ? 2 : 1)
                        .withNewSelector().addToMatchLabels("app", app).addToMatchLabels("color", color).endSelector()
                        .withNewTemplate().withNewMetadata().addToLabels("app", app).addToLabels("color", color).endMetadata()
                            .withNewSpec().addNewContainer().withName(app).withImage("nginx").endContainer().endSpec()
                        .endTemplate()
                    .endSpec()
                    .build()).create();
            client.secrets().inNamespace(NS).resource(new SecretBuilder()
                    .withNewMetadata().withName(app + "-" + color + "-db").withNamespace(NS)
                        .addToLabels("app", app).endMetadata()
                    .addToStringData("DB_URL", "jdbc:postgresql://db/" + app)
                    .build()).create();
        }
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withNewMetadata().withName(app + "-svc").withNamespace(NS).addToLabels("app", app).endMetadata()
                .withNewSpec().addToSelector("app", app).addToSelector("color", "blue").endSpec()
                .build()).create();
        client.network().v1().ingresses().inNamespace(NS).resource(new IngressBuilder()
                .withNewMetadata().withName(app + "-ingress").withNamespace(NS).endMetadata()
                .withNewSpec().endSpec()
                .build()).create();
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName("lily-progress-" + app).withNamespace(NS).addToLabels("app", app).endMetadata()
                .addToData("stage", "succeeded")
                .build()).create();
    }
}
