package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.MigrationSet;
import com.lily.cicd.schema.SchemaMigrator;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * green(v3) 이 트래픽을 받고 blue(v2) 가 replica 0 으로 남은 상태에서 롤백한다.
 * DB 작업은 {@link SchemaMigrator} mock 으로 대신한다 (실제 SQL 은 SchemaMigratorPostgresTest).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RollbackEngineTest {

    private static final String NS = "default";
    private static final String APP = "lily";
    private static final Map<String, String> DB_ENV = Map.of("DB_URL", "jdbc:postgresql://db/lily");
    private static final Map<String, String> SCRIPTS = Map.of(
            "V2__views.sql", "alter table posts add column view_count int not null default 0;",
            "U2__views.sql", "alter table posts drop column view_count;",
            "V3__subtitle.sql", "alter table posts add column subtitle text;",
            "U3__subtitle.sql", "alter table posts drop column subtitle;");

    private static KubernetesServer server;
    private KubernetesClient client;
    private SchemaMigrator migrator;
    private ScheduledExecutorService readyMarker;

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
        client.leases().inNamespace(NS).delete();
        migrator = mock(SchemaMigrator.class);
        when(migrator.versionsAbove(eq(DB_ENV), any())).thenReturn(List.of(MigrationVersion.fromVersion("3")));
    }

    @AfterEach
    void stopMarker() {
        if (readyMarker != null) {
            readyMarker.shutdownNow();
        }
    }

    @Test
    void 이전_슬롯으로_전환하고_스키마를_되돌린다() {
        givenReleases("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", SCRIPTS);
        startReadyMarker();

        DeploymentResultDto result = engine(8).rollback(APP, null, false).join();

        assertEquals("ROLLED_BACK", result.status());
        assertEquals("blue", result.activeColor());
        assertEquals("2", result.schemaVersion());
        assertEquals("blue", serviceColor());
        assertEquals(1, deployment("lily-blue").getSpec().getReplicas());
        assertEquals(0, deployment("lily-green").getSpec().getReplicas());
        verify(migrator).rollback(eq(DB_ENV), any(MigrationSet.class), eq(MigrationVersion.fromVersion("2")),
                eq(true), anyList());
        assertNull(client.leases().inNamespace(NS).withName("lily-lock-lily").get());
    }

    @Test
    void 이미_롤백한_상태면_거절하고_아무것도_바꾸지_않는다() {
        givenReleases("2026-09-30T11:00:00Z", "2026-09-30T10:00:00Z", SCRIPTS);

        DeployConflictException e = assertThrows(DeployConflictException.class,
                () -> engine(8).rollback(APP, null, false));

        assertTrue(e.getMessage().contains("이미 롤백한 상태"));
        assertEquals("green", serviceColor());
        assertEquals(0, deployment("lily-blue").getSpec().getReplicas());
    }

    @Test
    void 되돌릴_수_없는_스키마면_거절하고_appOnly면_앱만_되돌린다() {
        givenReleases("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", Map.of(
                "V2__views.sql", "alter table posts add column view_count int not null default 0;",
                "U2__views.sql", "alter table posts drop column view_count;",
                "V3__drop.sql", "-- lily:irreversible\nalter table posts drop column title;"));
        startReadyMarker();

        DeployConflictException e = assertThrows(DeployConflictException.class,
                () -> engine(8).rollback(APP, null, false));
        assertTrue(e.getMessage().contains("lily:irreversible"));
        assertTrue(e.getLogs().stream().anyMatch(l -> l.startsWith("rollback: refused")));
        assertEquals("green", serviceColor());

        DeploymentResultDto result = engine(8).rollback(APP, null, true).join();

        assertEquals("ROLLED_BACK", result.status());
        assertEquals("3", result.schemaVersion());
        assertEquals("blue", serviceColor());
        verify(migrator, never()).rollback(any(), any(), any(), anyBoolean(), anyList());
    }

    @Test
    void 스키마_되돌리기가_실패해도_앱_롤백은_유지하고_PARTIAL로_알린다() {
        givenReleases("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", SCRIPTS);
        startReadyMarker();
        doThrow(new IllegalStateException("U3 failed"))
                .when(migrator).rollback(any(), any(), any(), anyBoolean(), anyList());

        DeploymentResultDto result = engine(8).rollback(APP, null, false).join();

        assertEquals("PARTIAL", result.status());
        assertEquals("3", result.schemaVersion());
        assertEquals("blue", serviceColor());
        assertTrue(result.logs().stream().anyMatch(l -> l.contains("U3 failed")));
    }

    @Test
    void 이전_슬롯이_Ready가_안_되면_다시_0으로_내리고_트래픽과_스키마를_그대로_둔다() {
        givenReleases("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", SCRIPTS);

        DeploymentFailedException e = assertThrows(DeploymentFailedException.class,
                () -> engine(1).rollback(APP, null, false));

        assertTrue(e.getMessage().contains("Ready"));
        assertEquals("green", serviceColor());
        assertEquals(0, deployment("lily-blue").getSpec().getReplicas());
        verify(migrator, never()).rollback(any(), any(), any(), anyBoolean(), anyList());
    }

    @Test
    void 이전_슬롯이_없으면_거절한다() {
        givenService("green");
        givenDeployment("green", 1, "2026-09-30T10:00:00Z", "3");

        assertThrows(DeployConflictException.class, () -> engine(8).rollback(APP, null, false));
    }

    @Test
    void 이전_릴리스의_스키마_기록이_없으면_앱만_되돌릴_수_있다() {
        givenService("green");
        givenDeployment("blue", 0, "2026-09-30T09:00:00Z", null);
        givenDeployment("green", 1, "2026-09-30T10:00:00Z", "3");

        DeployConflictException e = assertThrows(DeployConflictException.class,
                () -> engine(8).rollback(APP, null, false));

        assertTrue(e.getMessage().contains("appOnly=true"));
    }

    @Test
    void 다른_작업이_진행_중이면_거절한다() {
        givenReleases("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", SCRIPTS);
        DeployLock lock = new DeployLock(client);

        try (DeployLock.Handle ignored = lock.acquire(NS, APP, "deploy")) {
            assertThrows(DeployConflictException.class, () -> engine(8).rollback(APP, null, false));
        }
    }

    @Test
    void canary_전략은_롤백을_지원하지_않는다() {
        DeployProperties properties = new DeployProperties();
        RollbackEngine canary = new RollbackEngine(properties, new CanaryDeploymentStrategy(client, properties),
                context -> DB_ENV, new Slf4jDeployLog(), new NoopDeployMonitor(), migrator,
                new ReleaseStore(client), new DeployLock(client));

        assertThrows(UnsupportedOperationException.class, () -> canary.rollback(APP, null, false));
    }

    @Test
    void 상태는_활성_슬롯과_롤백_가능_여부를_알려준다() {
        givenReleases("2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z", SCRIPTS);

        RollbackEngine.ReleaseStatus status = engine(8).status(APP, null);

        assertEquals("green", status.activeSlot());
        assertTrue(status.rollbackAvailable());
        assertEquals(2, status.slots().size());

        client.configMaps().inNamespace(NS).withName("lily-green-schema").delete();
        RollbackEngine.ReleaseStatus blocked = engine(8).status(APP, null);
        assertFalse(blocked.rollbackAvailable());
        assertTrue(blocked.reason().contains("v3"));
    }

    @Test
    void 서비스가_없으면_상태에_이유를_담는다() {
        RollbackEngine.ReleaseStatus status = engine(8).status(APP, null);

        assertNull(status.activeSlot());
        assertFalse(status.rollbackAvailable());
        assertTrue(status.reason().contains("Service"));
    }

    private RollbackEngine engine(long readinessSeconds) {
        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(readinessSeconds);
        properties.setReplicas(1);
        properties.setDrainSeconds(0);
        DatabaseProvisioner database = context -> DB_ENV;
        return new RollbackEngine(properties, new BlueGreenDeploymentStrategy(client, properties), database,
                new Slf4jDeployLog(), new NoopDeployMonitor(), migrator, new ReleaseStore(client), new DeployLock(client));
    }

    private void givenReleases(String blueDeployedAt, String greenDeployedAt, Map<String, String> greenScripts) {
        givenService("green");
        givenDeployment("blue", 0, blueDeployedAt, "2");
        givenDeployment("green", 1, greenDeployedAt, "3");
        new ReleaseStore(client).saveScripts(NS, APP, "green", MigrationSet.parse(greenScripts));
    }

    private void givenService(String color) {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withNewMetadata().withName("lily-svc").withNamespace(NS).endMetadata()
                .withNewSpec()
                    .withSelector(Map.of("app", APP, "color", color))
                    .addNewPort().withPort(80).withTargetPort(new IntOrString(8080)).endPort()
                .endSpec()
                .build()).create();
    }

    private void givenDeployment(String color, int replicas, String deployedAt, String schemaVersion) {
        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(APP + "-" + color)
                    .withNamespace(NS)
                    .addToLabels("app", APP)
                    .addToLabels("color", color)
                    .addToAnnotations(ReleaseStore.DEPLOYED_AT, deployedAt)
                    .addToAnnotations(ReleaseStore.DATABASE, "postgres")
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector().addToMatchLabels("app", APP).addToMatchLabels("color", color).endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", APP).addToLabels("color", color).endMetadata()
                        .withNewSpec().addNewContainer().withName(APP).withImage("image:" + color).endContainer().endSpec()
                    .endTemplate()
                .endSpec();
        if (schemaVersion != null) {
            builder.editMetadata().addToAnnotations(ReleaseStore.SCHEMA_VERSION, schemaVersion).endMetadata();
        }
        client.apps().deployments().inNamespace(NS).resource(builder.build()).create();
    }

    private void startReadyMarker() {
        readyMarker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "rollback-ready-marker");
            thread.setDaemon(true);
            return thread;
        });
        readyMarker.scheduleAtFixedRate(() -> {
            try {
                for (Deployment d : client.apps().deployments().inNamespace(NS).list().getItems()) {
                    Integer desired = d.getSpec().getReplicas();
                    Integer ready = d.getStatus() == null ? null : d.getStatus().getReadyReplicas();
                    if (desired == null || desired == 0 || desired.equals(ready)) {
                        continue;
                    }
                    client.apps().deployments().inNamespace(NS).withName(d.getMetadata().getName())
                            .editStatus(current -> new DeploymentBuilder(current).withNewStatus()
                                    .withReplicas(desired).withAvailableReplicas(desired).withReadyReplicas(desired)
                                    .endStatus().build());
                }
            } catch (Exception ignored) {
                // 다음 주기에 다시 시도한다
            }
        }, 30, 50, TimeUnit.MILLISECONDS);
    }

    private String serviceColor() {
        return client.services().inNamespace(NS).withName("lily-svc").get().getSpec().getSelector().get("color");
    }

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NS).withName(name).get();
    }
}
