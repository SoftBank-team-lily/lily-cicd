package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 온프레미스 PC 장애 때 대기 슬롯의 DB 를 클라우드 사본으로 바꾸고, PC 가 돌아오면 되돌린다 */
class AppDatabaseSwitchTest {

    private static final String NS = "default";
    private static final String TUNNEL = "jdbc:postgresql://172.31.10.248:20004/memo";
    private static final String COPY = "jdbc:postgresql://rds.example:5432/memo";
    private static KubernetesServer server;
    private KubernetesClient client;
    private DeployLock lock;
    private AppController controller;

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
        client.secrets().inNamespace(NS).delete();
        client.leases().inNamespace(NS).delete();
        lock = new DeployLock(client);
        controller = new AppController(client, new DeployProperties(), lock,
                new AppRemover(client, lock, new DeployProgress(), new DatabaseProvisioner() {
                    @Override
                    public Map<String, String> prepare(DeployContext context) {
                        return Map.of();
                    }
                }));
        givenStandby("memo");
    }

    @Test
    void 사본으로_바꾸면_원래_값을_남기고_있는_키만_바꾸고_Pod_를_다시_띄운다() {
        controller.switchDatabase("memo", null, new AppController.DatabaseRequest(Map.of(
                "DB_URL", COPY, "DB_PASSWORD", "copy-pw", "UNRELATED", "x")));

        Map<String, String> now = values("memo-blue-db");
        assertEquals(COPY, now.get("DB_URL"));
        assertEquals("copy-pw", now.get("DB_PASSWORD"));
        assertEquals("memo", now.get("DB_USERNAME"));
        assertNull(now.get("UNRELATED"));
        assertEquals(TUNNEL, values("memo-blue-db-pc").get("DB_URL"));
        String source = client.apps().deployments().inNamespace(NS).withName("memo-blue").get()
                .getSpec().getTemplate().getMetadata().getAnnotations().get(AppController.DATABASE_SOURCE);
        assertTrue(source.startsWith("copy@"));
    }

    @Test
    void 되돌리면_원래_값으로_돌아오고_남긴_값은_지운다() {
        controller.switchDatabase("memo", null, new AppController.DatabaseRequest(Map.of("DB_URL", COPY)));

        controller.restoreDatabase("memo", null);

        assertEquals(TUNNEL, values("memo-blue-db").get("DB_URL"));
        assertEquals("tunnel-pw", values("memo-blue-db").get("DB_PASSWORD"));
        assertNull(client.secrets().inNamespace(NS).withName("memo-blue-db-pc").get());
        String source = client.apps().deployments().inNamespace(NS).withName("memo-blue").get()
                .getSpec().getTemplate().getMetadata().getAnnotations().get(AppController.DATABASE_SOURCE);
        assertTrue(source.startsWith("pc@"));
    }

    @Test
    void 두_번_바꿔도_처음_원래_값을_지킨다() {
        controller.switchDatabase("memo", null, new AppController.DatabaseRequest(Map.of("DB_URL", COPY)));
        controller.switchDatabase("memo", null, new AppController.DatabaseRequest(Map.of("DB_URL", COPY + "2")));

        controller.restoreDatabase("memo", null);

        assertEquals(TUNNEL, values("memo-blue-db").get("DB_URL"));
    }

    @Test
    void 바꾼_적이_없으면_되돌리기는_아무것도_하지_않는다() {
        controller.restoreDatabase("memo", null);

        assertEquals(TUNNEL, values("memo-blue-db").get("DB_URL"));
        Map<String, String> annotations = client.apps().deployments().inNamespace(NS).withName("memo-blue").get()
                .getSpec().getTemplate().getMetadata().getAnnotations();
        assertTrue(annotations == null || !annotations.containsKey(AppController.DATABASE_SOURCE));
    }

    @Test
    void 바꿀_키가_없거나_Secret_이_없거나_배포_중이면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> controller.switchDatabase("memo", null,
                new AppController.DatabaseRequest(Map.of("OTHER", "x"))));

        client.secrets().inNamespace(NS).withName("memo-blue-db").delete();
        assertThrows(DeployConflictException.class, () -> controller.switchDatabase("memo", null,
                new AppController.DatabaseRequest(Map.of("DB_URL", COPY))));

        givenStandby("shop");
        try (DeployLock.Handle ignored = lock.acquire(NS, "shop", "deploy")) {
            assertThrows(DeployConflictException.class, () -> controller.switchDatabase("shop", null,
                    new AppController.DatabaseRequest(Map.of("DB_URL", COPY))));
        }
        assertEquals(TUNNEL.replace("memo", "shop"), values("shop-blue-db").get("DB_URL"));
    }

    @Test
    void 없는_앱이면_404() {
        assertEquals(404, controller.switchDatabase("ghost", null,
                new AppController.DatabaseRequest(Map.of("DB_URL", COPY))).getStatusCode().value());
    }

    @Test
    void 앱을_지우면_남긴_원래_값도_지운다() {
        controller.switchDatabase("memo", null, new AppController.DatabaseRequest(Map.of("DB_URL", COPY)));

        controller.delete("memo", null, false);

        assertNull(client.secrets().inNamespace(NS).withName("memo-blue-db-pc").get());
        assertNotNull(server);
    }

    private Map<String, String> values(String secret) {
        return DatabaseSecret.read(client.secrets().inNamespace(NS).withName(secret).get());
    }

    /** 대기 배포: blue 슬롯 하나, DB 는 내 PC 로 가는 역방향 터널 */
    private void givenStandby(String app) {
        client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                .withNewMetadata().withName(app + "-blue").withNamespace(NS)
                    .addToLabels("app", app).addToLabels("color", "blue").endMetadata()
                .withNewSpec().withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", app).addToMatchLabels("color", "blue").endSelector()
                    .withNewTemplate().withNewMetadata().addToLabels("app", app).addToLabels("color", "blue").endMetadata()
                        .withNewSpec().addNewContainer().withName(app).withImage("nginx").endContainer().endSpec()
                    .endTemplate()
                .endSpec()
                .build()).create();
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName(app + "-blue-db").withNamespace(NS).addToLabels("app", app).endMetadata()
                .addToStringData("DB_URL", TUNNEL.replace("memo", app))
                .addToStringData("DB_USERNAME", app)
                .addToStringData("DB_PASSWORD", "tunnel-pw")
                .build()).create();
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withNewMetadata().withName(app + "-svc").withNamespace(NS).addToLabels("app", app).endMetadata()
                .withNewSpec().addToSelector("app", app).addToSelector("color", "blue").endSpec()
                .build()).create();
    }
}
