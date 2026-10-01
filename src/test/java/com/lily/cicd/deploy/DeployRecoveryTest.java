package com.lily.cicd.deploy;

import com.lily.cicd.module.DeployStages;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.SchemaMigrator;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeployRecoveryTest {

    private static final String NS = "default";
    private static KubernetesServer server;
    private KubernetesClient client;

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
        client.configMaps().inNamespace(NS).delete();
        client.services().inNamespace(NS).delete();
        client.network().v1().ingresses().inNamespace(NS).delete();
    }

    @Test
    void 트래픽을_옮기기_전에_죽으면_스키마를_되돌리고_canary를_지운다() {
        AtomicInteger rollbacks = new AtomicInteger();
        SchemaMigrator migrator = new SchemaMigrator() {
            @Override
            public void rollback(Map<String, String> databaseEnv, com.lily.cicd.schema.MigrationSet scripts,
                                 org.flywaydb.core.api.MigrationVersion target, boolean backup, java.util.List<String> logs) {
                rollbacks.incrementAndGet();
                assertEquals(org.flywaydb.core.api.MigrationVersion.fromVersion("2"), target);
            }
        };
        givenCanary("blog");
        givenService("blue");
        givenRecovery("blog", "green", "2");
        givenProgress("blog", DeployStages.MIGRATION, Instant.EPOCH);

        recovery(migrator).recover();

        assertEquals(1, rollbacks.get());
        assertNull(client.services().inNamespace(NS).withName("blog-canary-svc").get());
        assertNull(client.network().v1().ingresses().inNamespace(NS).withName("blog-canary-ingress").get());
        assertNull(client.configMaps().inNamespace(NS).withName("lily-recovery-blog").get());
        assertEquals(DeployStages.FAILED, new DeployProgress(client).get(NS, "blog").orElseThrow().stage());
    }

    @Test
    void 트래픽이_이미_새_슬롯이면_스키마는_그대로_둔다() {
        AtomicInteger rollbacks = new AtomicInteger();
        SchemaMigrator migrator = new SchemaMigrator() {
            @Override
            public void rollback(Map<String, String> databaseEnv, com.lily.cicd.schema.MigrationSet scripts,
                                 org.flywaydb.core.api.MigrationVersion target, boolean backup, java.util.List<String> logs) {
                rollbacks.incrementAndGet();
            }
        };
        givenCanary("blog");
        givenService("green");
        givenRecovery("blog", "green", "2");
        givenProgress("blog", DeployStages.SERVICE, Instant.EPOCH);

        recovery(migrator).recover();

        assertEquals(0, rollbacks.get());
        assertNull(client.services().inNamespace(NS).withName("blog-canary-svc").get());
        assertEquals(DeployStages.FAILED, new DeployProgress(client).get(NS, "blog").orElseThrow().stage());
    }

    @Test
    void 진행_시각이_살아_있으면_건드리지_않는다() {
        givenCanary("blog");
        givenRecovery("blog", "green", "2");
        givenProgress("blog", DeployStages.MIGRATION, Instant.now());

        recovery(new SchemaMigrator()).recover();

        assertEquals("green", client.services().inNamespace(NS).withName("blog-canary-svc").get()
                .getSpec().getSelector().get("color"));
        assertEquals("2", client.configMaps().inNamespace(NS).withName("lily-recovery-blog").get()
                .getData().get("schemaFrom"));
    }

    private DeployRecovery recovery(SchemaMigrator migrator) {
        return new DeployRecovery(client, migrator, context -> Map.of("DB_URL", "jdbc:test"),
                new ReleaseStore(client), new DeployProgress(client));
    }

    private void givenCanary(String app) {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withNewMetadata().withName(app + "-canary-svc").withNamespace(NS).endMetadata()
                .withNewSpec().withSelector(Map.of("app", app, "color", "green"))
                    .addNewPort().withPort(80).endPort().endSpec()
                .build()).create();
        client.network().v1().ingresses().inNamespace(NS).resource(new IngressBuilder()
                .withNewMetadata().withName(app + "-canary-ingress").withNamespace(NS).endMetadata()
                .withNewSpec().withIngressClassName("nginx")
                    .addNewRule().withHost(app + ".apps.lilycloud.kr").withNewHttp()
                        .addNewPath().withPath("/").withPathType("Prefix")
                            .withNewBackend().withNewService().withName(app + "-canary-svc")
                                .withNewPort().withNumber(80).endPort()
                            .endService().endBackend()
                        .endPath()
                    .endHttp().endRule()
                .endSpec()
                .build()).create();
    }

    private void givenService(String color) {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withNewMetadata().withName("blog-svc").withNamespace(NS).endMetadata()
                .withNewSpec().withSelector(Map.of("app", "blog", "color", color))
                    .addNewPort().withPort(80).endPort().endSpec()
                .build()).create();
    }

    private void givenRecovery(String app, String target, String schemaFrom) {
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName("lily-recovery-" + app).withNamespace(NS)
                    .addToLabels("app", app).addToLabels("lily.io/recovery", "true").endMetadata()
                .addToData("targetSlot", target)
                .addToData("schemaFrom", schemaFrom)
                .addToData("database", "postgres")
                .build()).create();
    }

    private void givenProgress(String app, String stage, Instant updatedAt) {
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName("lily-progress-" + app).withNamespace(NS)
                    .addToLabels("app", app).addToLabels("lily.io/progress", "true").endMetadata()
                .addToData("stage", stage)
                .addToData("detail", "test")
                .addToData("updatedAt", updatedAt.toString())
                .build()).create();
    }
}
