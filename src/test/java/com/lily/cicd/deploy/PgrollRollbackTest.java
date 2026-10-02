package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.PgrollMigrator;
import com.lily.cicd.schema.SchemaMigrator;
import com.lily.cicd.schema.SchemaOperationException;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * green(pgroll 02_add_slug, 롤백 창 열림) 이 트래픽을 받고 blue(01_create_posts) 가 replica 0 으로 남은 상태에서
 * 롤백하고, 창이 지나면 complete 한다. pgroll 실행은 mock 이다.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class PgrollRollbackTest {

    private static final String NS = "default";
    private static final String APP = "lily";
    private static final Map<String, String> DB_ENV = Map.of(
            "DB_URL", "jdbc:postgresql://db/lily", "DB_USERNAME", "lily", "DB_PASSWORD", "pw");

    private static KubernetesServer server;
    private KubernetesClient client;
    private PgrollMigrator migrator;
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
        migrator = mock(PgrollMigrator.class);
        givenService("green");
        givenDeployment("blue", 0, "2025-12-31T09:00:00Z", "01_create_posts", null, null);
    }

    @AfterEach
    void stopMarker() {
        if (readyMarker != null) {
            readyMarker.shutdownNow();
        }
    }

    @Test
    void 롤백_창_안이면_이전_슬롯으로_전환하고_현재_슬롯을_내린_뒤_pgroll_rollback한다() {
        givenGreen("active", "2026-01-01T00:00:00Z");
        when(migrator.active(DB_ENV)).thenReturn(Optional.of("02_add_slug"));
        startReadyMarker();

        DeploymentResultDto result = rollbackEngine().rollback(APP, null, false).join();

        assertEquals("ROLLED_BACK", result.status());
        assertEquals("blue", result.activeColor());
        assertEquals("01_create_posts", result.schemaVersion());
        assertEquals("blue", serviceColor());
        assertEquals(0, deployment("lily-green").getSpec().getReplicas());
        verify(migrator).rollback(eq(DB_ENV), eq("02_add_slug"), anyList());
        assertEquals("rolled-back",
                deployment("lily-green").getMetadata().getAnnotations().get(ReleaseStore.PGROLL_STATE));
    }

    @Test
    void complete된_pgroll_릴리스는_롤백을_거절하고_트래픽을_그대로_둔다() {
        givenGreen("active", "2026-01-01T00:00:00Z");
        when(migrator.active(DB_ENV)).thenReturn(Optional.empty());

        DeployConflictException e = assertThrows(DeployConflictException.class,
                () -> rollbackEngine().rollback(APP, null, false));

        assertTrue(e.getMessage().contains("complete"), e.getMessage());
        assertEquals("green", serviceColor());
        verify(migrator, never()).rollback(any(), any(), anyList());
    }

    @Test
    void pgroll_릴리스는_appOnly_롤백을_거절한다() {
        givenGreen("active", "2026-01-01T00:00:00Z");

        DeployConflictException e = assertThrows(DeployConflictException.class,
                () -> rollbackEngine().rollback(APP, null, true));

        assertTrue(e.getMessage().contains("앱만"), e.getMessage());
        assertEquals("green", serviceColor());
    }

    @Test
    void pgroll_rollback이_실패해도_앱_롤백은_유지하고_PARTIAL로_알린다() {
        givenGreen("active", "2026-01-01T00:00:00Z");
        when(migrator.active(DB_ENV)).thenReturn(Optional.of("02_add_slug"));
        doThrow(new SchemaOperationException("pgroll rollback 실패: lock timeout", null))
                .when(migrator).rollback(eq(DB_ENV), eq("02_add_slug"), anyList());
        startReadyMarker();

        DeploymentResultDto result = rollbackEngine().rollback(APP, null, false).join();

        assertEquals("PARTIAL", result.status());
        assertEquals("02_add_slug", result.schemaVersion());
        assertEquals("blue", serviceColor());
    }

    @Test
    void status는_롤백_창이_열려_있으면_가능_complete되면_불가로_알린다() {
        givenGreen("active", "2026-01-01T00:00:00Z");
        assertTrue(rollbackEngine().status(APP, null).rollbackAvailable());

        new ReleaseStore(client).markPgroll(NS, APP, "green", "complete", null);
        RollbackEngine.ReleaseStatus closed = rollbackEngine().status(APP, null);

        assertFalse(closed.rollbackAvailable());
        assertTrue(closed.reason().contains("complete"), closed.reason());
    }

    @Test
    void 롤백_창이_지나면_complete하고_상태를_complete로_바꾼다() {
        givenGreen("active", "2026-01-01T00:00:00Z");
        when(migrator.active(DB_ENV)).thenReturn(Optional.of("02_add_slug"));

        completer().completeDue();

        verify(migrator).complete(eq(DB_ENV), eq("02_add_slug"), anyList());
        assertEquals("complete", deployment("lily-green").getMetadata().getAnnotations().get(ReleaseStore.PGROLL_STATE));
    }

    @Test
    void 롤백_창이_남았으면_complete하지_않고_바로_complete_API는_창을_닫는다() {
        givenGreen("active", "2999-01-01T00:00:00Z");
        when(migrator.active(DB_ENV)).thenReturn(Optional.of("02_add_slug"));

        completer().completeDue();
        verify(migrator, never()).complete(any(), any(), anyList());

        List<String> logs = completer().completeNow(NS, APP);

        verify(migrator).complete(eq(DB_ENV), eq("02_add_slug"), anyList());
        assertEquals("complete", deployment("lily-green").getMetadata().getAnnotations().get(ReleaseStore.PGROLL_STATE));
        assertThrows(DeployConflictException.class, () -> completer().completeNow(NS, APP));
        assertTrue(logs.isEmpty() || logs.get(0).startsWith("schema:"), logs.toString());
    }

    @Test
    void 다음_배포가_먼저_complete했으면_DB는_건드리지_않고_상태만_complete로_맞춘다() {
        givenGreen("active", "2026-01-01T00:00:00Z");
        when(migrator.active(DB_ENV)).thenReturn(Optional.of("03_other"));

        completer().completeDue();

        verify(migrator, never()).complete(any(), any(), anyList());
        assertEquals("complete", deployment("lily-green").getMetadata().getAnnotations().get(ReleaseStore.PGROLL_STATE));
    }

    private PgrollSchema pgroll() {
        return new PgrollSchema(migrator, database(), new ReleaseStore(client), client, Duration.ofMinutes(10));
    }

    private DatabaseProvisioner database() {
        return context -> DB_ENV;
    }

    private RollbackEngine rollbackEngine() {
        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(8);
        properties.setReplicas(1);
        properties.setDrainSeconds(0);
        return new RollbackEngine(properties, new BlueGreenDeploymentStrategy(client, properties), database(),
                new Slf4jDeployLog(), new NoopDeployMonitor(), mock(SchemaMigrator.class), new ReleaseStore(client),
                new DeployLock(client), pgroll());
    }

    private PgrollCompleter completer() {
        return new PgrollCompleter(pgroll(), database(), new ReleaseStore(client), new DeployLock(client),
                Duration.ofSeconds(30));
    }

    private void givenGreen(String state, String completeAfter) {
        givenDeployment("green", 1, "2025-12-31T10:00:00Z", "02_add_slug", state, completeAfter);
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

    private void givenDeployment(String color, int replicas, String deployedAt, String migration,
                                 String state, String completeAfter) {
        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(APP + "-" + color)
                    .withNamespace(NS)
                    .addToLabels("app", APP)
                    .addToLabels("color", color)
                    .addToAnnotations(ReleaseStore.DEPLOYED_AT, deployedAt)
                    .addToAnnotations(ReleaseStore.DATABASE, "postgres")
                    .addToAnnotations(ReleaseStore.SCHEMA_ENGINE, "pgroll")
                    .addToAnnotations(ReleaseStore.SCHEMA_VERSION, migration)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector().addToMatchLabels("app", APP).addToMatchLabels("color", color).endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", APP).addToLabels("color", color).endMetadata()
                        .withNewSpec().addNewContainer().withName(APP).withImage("image:" + color).endContainer().endSpec()
                    .endTemplate()
                .endSpec();
        if (state != null) {
            builder.editMetadata()
                    .addToAnnotations(ReleaseStore.PGROLL_STATE, state)
                    .addToAnnotations(ReleaseStore.PGROLL_COMPLETE_AFTER, completeAfter)
                    .endMetadata();
        }
        client.apps().deployments().inNamespace(NS).resource(builder.build()).create();
    }

    private void startReadyMarker() {
        readyMarker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "pgroll-rollback-ready-marker");
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
