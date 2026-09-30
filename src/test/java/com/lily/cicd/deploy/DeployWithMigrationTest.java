package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.MigrationSet;
import com.lily.cicd.schema.SchemaChange;
import com.lily.cicd.schema.SchemaMigrator;
import com.lily.cicd.schema.SchemaOperationException;
import io.fabric8.kubernetes.api.model.EnvVar;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배포 요청에 migrations 가 있을 때의 순서와 실패 처리. SQL 실행은 mock 이다 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DeployWithMigrationTest {

    private static final String NS = "default";
    private static final Map<String, String> DB_ENV = Map.of("DB_URL", "jdbc:postgresql://db/lily");
    private static final Map<String, String> SCRIPTS = Map.of(
            "V2__views.sql", "alter table posts add column view_count int not null default 0;",
            "U2__views.sql", "alter table posts drop column view_count;");
    private static final MigrationVersion V1 = MigrationVersion.fromVersion("1");
    private static final MigrationVersion V2 = MigrationVersion.fromVersion("2");

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
        client.network().v1().ingresses().inNamespace(NS).delete();
        client.configMaps().inNamespace(NS).delete();
        client.leases().inNamespace(NS).delete();
        migrator = mock(SchemaMigrator.class);
    }

    @AfterEach
    void stopMarker() {
        if (readyMarker != null) {
            readyMarker.shutdownNow();
        }
    }

    @Test
    void 스키마를_먼저_옮기고_릴리스를_기록하고_앱의_Flyway를_끈다() {
        startReadyMarker();
        when(migrator.migrate(eq(DB_ENV), any(MigrationSet.class), anyList()))
                .thenReturn(new SchemaChange(V1, V2, List.of(V2)));

        DeploymentResultDto result = engine(8).deploy(command("postgres", SCRIPTS)).join();

        assertEquals("SUCCESS", result.status());
        assertEquals("2", result.schemaVersion());
        Deployment blue = deployment("lily-blue");
        assertEquals("false", env(blue, "SPRING_FLYWAY_ENABLED"));
        assertEquals("jdbc:postgresql://db/lily", env(blue, "DB_URL"));
        Map<String, String> annotations = blue.getMetadata().getAnnotations();
        assertEquals("2", annotations.get(ReleaseStore.SCHEMA_VERSION));
        assertEquals("postgres", annotations.get(ReleaseStore.DATABASE));
        assertNotNull(annotations.get(ReleaseStore.DEPLOYED_AT));
        assertEquals(SCRIPTS, client.configMaps().inNamespace(NS).withName("lily-blue-schema").get().getData());
        assertNull(client.leases().inNamespace(NS).withName("lily-lock-lily").get());
    }

    @Test
    void 마이그레이션이_없으면_스키마_기록_없이_이전처럼_배포한다() {
        startReadyMarker();

        DeploymentResultDto result = engine(8).deploy(command("postgres", Map.of())).join();

        assertNull(result.schemaVersion());
        Deployment blue = deployment("lily-blue");
        assertNull(env(blue, "SPRING_FLYWAY_ENABLED"));
        assertNull(blue.getMetadata().getAnnotations().get(ReleaseStore.SCHEMA_VERSION));
        assertNotNull(blue.getMetadata().getAnnotations().get(ReleaseStore.DEPLOYED_AT));
        verify(migrator, never()).migrate(any(), any(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void Ready_전에_실패하면_이번에_적용한_버전을_되돌리고_결과를_응답_로그에_남긴다() {
        when(migrator.migrate(eq(DB_ENV), any(MigrationSet.class), anyList()))
                .thenReturn(new SchemaChange(V1, V2, List.of(V2)));
        doAnswer(inv -> ((List<String>) inv.getArgument(4)).add("schema: reverted [U2__views.sql] -> now 1"))
                .when(migrator).rollback(any(), any(), any(), anyBoolean(), anyList());

        DeploymentFailedException e = assertThrows(DeploymentFailedException.class,
                () -> engine(1).deploy(command("postgres", SCRIPTS)));

        assertTrue(e.getMessage().contains("Ready"));
        assertTrue(e.getLogs().contains("schema: reverted [U2__views.sql] -> now 1"), e.getLogs()::toString);
        assertNull(deployment("lily-blue"));
        verify(migrator).rollback(eq(DB_ENV), any(MigrationSet.class), eq(V1), eq(false), anyList());
    }

    @Test
    void 적용한_버전이_없으면_되돌리지_않는다() {
        when(migrator.migrate(eq(DB_ENV), any(MigrationSet.class), anyList()))
                .thenReturn(new SchemaChange(V2, V2, List.of()));

        assertThrows(DeploymentFailedException.class, () -> engine(1).deploy(command("postgres", SCRIPTS)));

        verify(migrator, never()).rollback(any(), any(), any(), anyBoolean(), anyList());
    }

    @Test
    void 마이그레이션_실패는_슬롯을_만들지_않는다() {
        when(migrator.migrate(eq(DB_ENV), any(MigrationSet.class), anyList()))
                .thenThrow(new SchemaOperationException("V2__views.sql 실행 실패", null));

        DeploymentFailedException e = assertThrows(DeploymentFailedException.class,
                () -> engine(8).deploy(command("postgres", SCRIPTS)));

        assertTrue(e.getMessage().startsWith("스키마 마이그레이션 실패"));
        assertNull(deployment("lily-blue"));
    }

    @Test
    void lint_위반은_400으로_그대로_던진다() {
        when(migrator.migrate(eq(DB_ENV), any(MigrationSet.class), anyList()))
                .thenThrow(new IllegalArgumentException("마이그레이션 규칙 위반"));

        assertThrows(IllegalArgumentException.class, () -> engine(8).deploy(command("postgres", SCRIPTS)));
        assertNull(deployment("lily-blue"));
    }

    @Test
    void database_없이_migrations만_보내면_거절한다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine(8).deploy(command(null, SCRIPTS)));

        assertTrue(e.getMessage().contains("database"));
    }

    @Test
    void 같은_앱의_다른_작업이_진행_중이면_거절한다() {
        try (DeployLock.Handle ignored = new DeployLock(client).acquire(NS, "lily", "rollback")) {
            assertThrows(DeployConflictException.class, () -> engine(8).deploy(command("postgres", Map.of())));
        }
    }

    private DeploymentEngine engine(long readinessSeconds) {
        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(readinessSeconds);
        return new DeploymentEngine(properties, new BlueGreenDeploymentStrategy(client, properties),
                context -> context.database() == null ? Map.of() : DB_ENV,
                new NginxIngressRouter(client), new Slf4jDeployLog(), new NoopDeployMonitor(),
                migrator, new ReleaseStore(client), new DeployLock(client));
    }

    private static DeployCommand command(String database, Map<String, String> migrations) {
        return new DeployCommand("lily", "image:2", 8080, null, null, null, null, "2", null, Map.of(),
                database, null, migrations);
    }

    private void startReadyMarker() {
        readyMarker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "migration-ready-marker");
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

    private Deployment deployment(String name) {
        return client.apps().deployments().inNamespace(NS).withName(name).get();
    }

    private static String env(Deployment deployment, String name) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .filter(e -> name.equals(e.getName())).map(EnvVar::getValue).findFirst().orElse(null);
    }
}
