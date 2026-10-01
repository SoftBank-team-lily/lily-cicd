package com.lily.cicd.release;

import com.lily.cicd.schema.MigrationSet;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseStoreAndLockTest {

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
        client.apps().deployments().inNamespace(NS).delete();
        client.configMaps().inNamespace(NS).delete();
        client.leases().inNamespace(NS).delete();
    }

    @Test
    void 스크립트를_슬롯별_ConfigMap에_보관하고_되살린다() {
        ReleaseStore store = new ReleaseStore(client);
        MigrationSet scripts = MigrationSet.parse(Map.of("V1__a.sql", "create table a(id int);", "U1__a.sql", "drop table a;"));

        store.saveScripts(NS, "blog", "green", scripts);

        assertEquals("green", client.configMaps().inNamespace(NS).withName("blog-green-schema").get()
                .getMetadata().getLabels().get("lily.io/slot"));
        assertEquals(scripts.files(), store.loadScripts(NS, "blog", "green").files());
        assertTrue(store.loadScripts(NS, "blog", "blue").isEmpty());

        store.saveScripts(NS, "blog", "green", MigrationSet.empty());
        assertNull(client.configMaps().inNamespace(NS).withName("blog-green-schema").get());
    }

    @Test
    void 슬롯_Deployment에_릴리스를_기록하고_읽는다() {
        ReleaseStore store = new ReleaseStore(client);
        givenDeployment("blog-blue", 0);
        Instant deployedAt = Instant.parse("2026-09-30T10:00:00Z");

        store.annotate(NS, "blog", "blue", deployedAt, "3", "postgres");

        ReleaseStore.Release release = store.read(NS, "blog", "blue").orElseThrow();
        assertEquals(deployedAt, release.deployedAt());
        assertEquals("3", release.schemaVersion());
        assertEquals("postgres", release.database());
        assertEquals(0, release.replicas());
        assertEquals("image:1", release.image());
        assertTrue(release.schemaManaged());
        assertTrue(store.read(NS, "blog", "green").isEmpty());
    }

    @Test
    void 스키마를_맡지_않는_앱은_버전을_남기지_않고_슬롯이_없으면_넘어간다() {
        ReleaseStore store = new ReleaseStore(client);
        givenDeployment("blog-blue", 1);

        store.annotate(NS, "blog", "blue", Instant.now(), null, null);
        assertDoesNotThrow(() -> store.annotate(NS, "blog", "green", Instant.now(), "1", "postgres"));

        ReleaseStore.Release release = store.read(NS, "blog", "blue").orElseThrow();
        assertNull(release.schemaVersion());
        assertNull(release.database());
        assertNotNull(release.deployedAt());
    }

    @Test
    void 잠금은_한_번에_하나만_잡히고_닫으면_풀린다() {
        DeployLock lock = new DeployLock(client);

        DeployLock.Handle first = lock.acquire(NS, "blog", "deploy");
        DeployConflictException e = assertThrows(DeployConflictException.class,
                () -> lock.acquire(NS, "blog", "rollback"));
        assertTrue(e.getMessage().contains("deploy/"));
        assertDoesNotThrow(() -> lock.acquire(NS, "other", "deploy").close());

        first.close();
        assertNull(client.leases().inNamespace(NS).withName("lily-lock-blog").get());
        assertDoesNotThrow(() -> lock.acquire(NS, "blog", "rollback").close());
    }

    @Test
    void 만료된_잠금은_가져간다() {
        ZonedDateTime old = ZonedDateTime.now(ZoneOffset.UTC).minus(DeployLock.EXPIRY).minusMinutes(1);
        client.leases().inNamespace(NS).resource(new LeaseBuilder()
                .withNewMetadata().withName("lily-lock-blog").withNamespace(NS).endMetadata()
                .withNewSpec().withHolderIdentity("deploy/dead").withRenewTime(old).endSpec()
                .build()).create();

        DeployLock.Handle handle = new DeployLock(client).acquire(NS, "blog", "rollback");

        assertTrue(client.leases().inNamespace(NS).withName("lily-lock-blog").get()
                .getSpec().getHolderIdentity().startsWith("rollback/"));
        handle.close();
    }

    @Test
    void 잠금을_갱신하면_시각이_앞으로_간다() {
        DeployLock.Handle handle = new DeployLock(client).acquire(NS, "blog", "deploy");
        ZonedDateTime stale = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        client.leases().inNamespace(NS).withName("lily-lock-blog").edit(l -> new LeaseBuilder(l)
                .editSpec().withRenewTime(stale).endSpec().build());

        handle.renew();

        assertTrue(client.leases().inNamespace(NS).withName("lily-lock-blog").get()
                .getSpec().getRenewTime().toInstant().isAfter(stale.toInstant()));
        handle.close();
    }

    @Test
    void 남이_가져간_잠금은_닫아도_지우지_않는다() {
        DeployLock lock = new DeployLock(client);
        DeployLock.Handle mine = lock.acquire(NS, "blog", "deploy");
        client.leases().inNamespace(NS).withName("lily-lock-blog").edit(l -> new LeaseBuilder(l)
                .editSpec().withHolderIdentity("rollback/other").endSpec().build());

        mine.close();

        assertNotNull(client.leases().inNamespace(NS).withName("lily-lock-blog").get());
    }

    private void givenDeployment(String name, int replicas) {
        client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                .withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector().addToMatchLabels("app", "blog").endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", "blog").endMetadata()
                        .withNewSpec().addNewContainer().withName("blog").withImage("image:1").endContainer().endSpec()
                    .endTemplate()
                .endSpec()
                .build()).create();
    }
}
