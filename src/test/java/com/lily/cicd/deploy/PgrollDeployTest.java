package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.NoopDeployMonitor;
import com.lily.cicd.module.Slf4jDeployLog;
import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.PgrollMigrator;
import com.lily.cicd.schema.PgrollMigrator.PgrollChange;
import com.lily.cicd.schema.PgrollSet;
import com.lily.cicd.schema.SchemaMigrator;
import com.lily.cicd.schema.SchemaOperationException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배포 요청의 migrations 가 pgroll 파일일 때의 순서와 실패 처리. pgroll 실행은 mock 이다 (실제 CLI 는 PgrollMigratorPostgresTest) */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class PgrollDeployTest {

    private static final String NS = "default";
    private static final Map<String, String> DB_ENV = Map.of(
            "DB_URL", "jdbc:postgresql://db/lily", "DB_USERNAME", "lily", "DB_PASSWORD", "pw");
    private static final Map<String, String> FILES = Map.of(
            "01_create_posts.yaml", "operations: []", "02_add_slug.yaml", "operations: []");

    private static KubernetesServer server;
    private KubernetesClient client;
    private PgrollMigrator migrator;
    private List<DeployContext> enabled;
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
        client.secrets().inNamespace(NS).delete();
        client.leases().inNamespace(NS).delete();
        migrator = mock(PgrollMigrator.class);
        enabled = new ArrayList<>();
    }

    @AfterEach
    void stopMarker() {
        if (readyMarker != null) {
            readyMarker.shutdownNow();
        }
    }

    @Test
    void pgroll을_켜고_시작한_뒤_새_슬롯을_버전_스키마로_띄우고_롤백_창을_연다() {
        startReadyMarker();
        when(migrator.migrate(eq(DB_ENV), any(PgrollSet.class), eq(false), anyList()))
                .thenReturn(new PgrollChange("01_create_posts", "02_add_slug", true, null));

        DeploymentResultDto result = engine(8).deploy(command(FILES)).join();

        assertEquals("SUCCESS", result.status());
        assertEquals("02_add_slug", result.schemaVersion());
        assertEquals(1, enabled.size());
        Deployment blue = deployment("lily-blue");
        assertEquals("jdbc:postgresql://db/lily?currentSchema=public_02_add_slug", env(blue, "DB_URL"));
        assertEquals("public_02_add_slug", env(blue, "LILY_DB_SCHEMA"));
        assertEquals("false", env(blue, "SPRING_FLYWAY_ENABLED"));
        Map<String, String> annotations = blue.getMetadata().getAnnotations();
        assertEquals("pgroll", annotations.get(ReleaseStore.SCHEMA_ENGINE));
        assertEquals("02_add_slug", annotations.get(ReleaseStore.SCHEMA_VERSION));
        assertEquals("active", annotations.get(ReleaseStore.PGROLL_STATE));
        assertNotNull(annotations.get(ReleaseStore.PGROLL_COMPLETE_AFTER));
    }

    @Test
    void Ready_전에_실패하면_시작한_pgroll_마이그레이션을_되돌린다() {
        when(migrator.migrate(eq(DB_ENV), any(PgrollSet.class), anyBoolean(), anyList()))
                .thenReturn(new PgrollChange(null, "02_add_slug", true, null));

        DeploymentFailedException failed = assertThrows(DeploymentFailedException.class,
                () -> engine(1).deploy(command(FILES)));
        verify(migrator).rollback(eq(DB_ENV), eq("02_add_slug"), anyList());
        assertNull(deployment("lily-blue"));
        assertTrue(failed.getMessage().contains("Ready"), failed.getMessage());
    }

    @Test
    void 마이그레이션_파일이_없어도_pgroll로_관리하던_DB면_최신_버전_스키마로_접속한다() {
        startReadyMarker();
        when(migrator.latest(DB_ENV)).thenReturn(Optional.of("02_add_slug"));

        DeploymentResultDto result = engine(8).deploy(command(Map.of())).join();

        assertEquals("02_add_slug", result.schemaVersion());
        Deployment blue = deployment("lily-blue");
        assertEquals("jdbc:postgresql://db/lily?currentSchema=public_02_add_slug", env(blue, "DB_URL"));
        assertEquals("pgroll", blue.getMetadata().getAnnotations().get(ReleaseStore.SCHEMA_ENGINE));
        assertNull(blue.getMetadata().getAnnotations().get(ReleaseStore.PGROLL_STATE));
        verify(migrator, never()).migrate(any(), any(), anyBoolean(), anyList());
    }

    @Test
    void 다른_클라우드의_DB를_따라가는_배포는_pgroll_최신_버전_스키마로_접속하고_스키마를_기록하지_않는다() {
        startReadyMarker();
        when(migrator.latest(DB_ENV)).thenReturn(Optional.of("02_add_slug"));

        DeploymentResultDto result = engine(8).deploy(follower(true)).join();

        assertEquals("SUCCESS", result.status());
        assertNull(result.schemaVersion());
        Deployment blue = deployment("lily-blue");
        assertEquals("jdbc:postgresql://db/lily?currentSchema=public_02_add_slug", env(blue, "DB_URL"));
        assertEquals("public_02_add_slug", env(blue, "LILY_DB_SCHEMA"));
        Map<String, String> annotations = blue.getMetadata().getAnnotations();
        assertNull(annotations.get(ReleaseStore.SCHEMA_ENGINE));
        assertNull(annotations.get(ReleaseStore.SCHEMA_VERSION));
        verify(migrator, never()).migrate(any(), any(), anyBoolean(), anyList());
        assertTrue(enabled.isEmpty());
    }

    @Test
    void DB를_받기만_한_배포는_pgroll_버전_스키마를_붙이지_않는다() {
        startReadyMarker();

        engine(8).deploy(follower(false)).join();

        assertEquals("jdbc:postgresql://db/lily", env(deployment("lily-blue"), "DB_URL"));
        verify(migrator, never()).latest(any());
    }

    @Test
    void DB_접속_정보_없이_pgroll을_따라가라고_하면_400으로_거절한다() {
        DeployCommand followOnly = new DeployCommand("lily", "image:2", 8080, null, null, null, null, "2", null,
                Map.of(), null, null, Map.of(), null, Map.of(), null, true);

        assertThrows(IllegalArgumentException.class, () -> engine(8).deploy(followOnly));
        assertNull(deployment("lily-blue"));
    }

    @Test
    void pgroll로_관리하던_DB에_SQL_마이그레이션을_보내면_400으로_거절하고_적용하지_않는다() {
        when(migrator.latest(DB_ENV)).thenReturn(Optional.of("02_add_slug"));
        SchemaMigrator flyway = mock(SchemaMigrator.class);

        assertThrows(IllegalArgumentException.class, () -> engine(8, flyway).deploy(command(Map.of(
                "V3__col.sql", "alter table posts add column x int;", "U3__col.sql", "alter table posts drop column x;"))));

        verify(flyway, never()).migrate(any(), any(), anyList());
        assertNull(deployment("lily-blue"));
    }

    @Test
    void 가중치_전환_중_실패해_새_슬롯이_지워졌으면_시작한_pgroll_마이그레이션을_바로_되돌린다() {
        startReadyMarker();
        when(migrator.migrate(eq(DB_ENV), any(PgrollSet.class), anyBoolean(), anyList()))
                .thenReturn(new PgrollChange("01_create_posts", "02_add_slug", true, null));

        DeploymentFailedException e = assertThrows(DeploymentFailedException.class,
                () -> engine(8, mock(SchemaMigrator.class), properties -> failingSwitch(properties, true))
                        .deploy(command(FILES)));

        verify(migrator).rollback(eq(DB_ENV), eq("02_add_slug"), anyList());
        assertTrue(e.getMessage().contains("스키마는 이전 버전으로 되돌림"), e.getMessage());
        assertNull(deployment("lily-blue"));
    }

    @Test
    void 가중치_전환_중_실패해도_새_슬롯이_남아_있으면_pgroll_마이그레이션을_그대로_둔다() {
        startReadyMarker();
        when(migrator.migrate(eq(DB_ENV), any(PgrollSet.class), anyBoolean(), anyList()))
                .thenReturn(new PgrollChange("01_create_posts", "02_add_slug", true, null));

        DeploymentFailedException e = assertThrows(DeploymentFailedException.class,
                () -> engine(8, mock(SchemaMigrator.class), properties -> failingSwitch(properties, false))
                        .deploy(command(FILES)));

        verify(migrator, never()).rollback(any(), any(), anyList());
        assertTrue(e.getLogs().stream().anyMatch(l -> l.contains("그대로 둔다")), e.getLogs().toString());
    }

    /** 카나리 가중치를 올리다 실패한 상황. removeTarget 이면 전략이 새 슬롯을 지우고 이전 슬롯으로 되돌린 뒤다 */
    private DeploymentStrategy failingSwitch(DeployProperties properties, boolean removeTarget) {
        return new BlueGreenDeploymentStrategy(client, properties) {
            @Override
            public void switchTraffic(DeployCommand command, String namespace, SlotPlan plan, List<String> logs) {
                if (removeTarget) {
                    client.apps().deployments().inNamespace(namespace).withName("lily-" + plan.target()).delete();
                }
                throw new DeploymentFailedException("canary 트래픽 전환 실패: lily-svc", logs, null);
            }
        };
    }

    @Test
    void pgroll_시작이_실패하면_슬롯을_만들지_않는다() {
        when(migrator.migrate(eq(DB_ENV), any(PgrollSet.class), anyBoolean(), anyList()))
                .thenThrow(new SchemaOperationException("pgroll start 실패: lock timeout", null));

        assertThrows(DeploymentFailedException.class, () -> engine(8).deploy(command(FILES)));
        assertNull(deployment("lily-blue"));
    }

    @Test
    void pgroll_규칙_위반과_postgres가_아닌_DB는_400으로_던진다() {
        when(migrator.migrate(eq(DB_ENV), any(PgrollSet.class), anyBoolean(), anyList()))
                .thenThrow(new IllegalArgumentException("pgroll 마이그레이션은 배포마다 하나만"));

        assertThrows(IllegalArgumentException.class, () -> engine(8).deploy(command(FILES)));

        DeployCommand mysql = new DeployCommand("lily", "image:2", 8080, null, null, null, null, "2", null, Map.of(),
                "mysql", null, FILES);
        assertThrows(IllegalArgumentException.class, () -> engine(8).deploy(mysql));
    }

    private DeploymentEngine engine(long readinessSeconds) {
        return engine(readinessSeconds, mock(SchemaMigrator.class));
    }

    private DeploymentEngine engine(long readinessSeconds, SchemaMigrator flyway) {
        return engine(readinessSeconds, flyway, properties -> new BlueGreenDeploymentStrategy(client, properties));
    }

    private DeploymentEngine engine(long readinessSeconds, SchemaMigrator flyway,
                                    java.util.function.Function<DeployProperties, DeploymentStrategy> strategy) {
        DeployProperties properties = new DeployProperties();
        properties.setReadinessTimeoutSeconds(readinessSeconds);
        properties.setReplicas(1);
        properties.setDrainSeconds(0);
        DatabaseProvisioner database = new DatabaseProvisioner() {
            @Override
            public Map<String, String> prepare(DeployContext context) {
                return context.database() == null ? Map.of() : DB_ENV;
            }

            @Override
            public void enablePgroll(DeployContext context) {
                enabled.add(context);
            }
        };
        ReleaseStore releases = new ReleaseStore(client);
        PgrollSchema pgroll = new PgrollSchema(migrator, database, releases, client, Duration.ofMinutes(10));
        return new DeploymentEngine(properties, strategy.apply(properties), database,
                new NginxIngressRouter(client), new Slf4jDeployLog(), new NoopDeployMonitor(),
                flyway, releases, new DeployLock(client), null, new DeployProgress(), null, pgroll);
    }

    private static DeployCommand command(Map<String, String> migrations) {
        return new DeployCommand("lily", "image:2", 8080, null, null, null, null, "2", null, Map.of(),
                "postgres", null, migrations);
    }

    /** 멀티클라우드 두 번째 클라우드 배포: DB 는 만들지 않고 받은 접속 정보를 쓴다 */
    private static DeployCommand follower(boolean followPgroll) {
        return new DeployCommand("lily", "image:2", 8080, null, null, null, null, "2", null, Map.of(),
                null, null, Map.of(), null, DB_ENV, null, followPgroll);
    }

    private void startReadyMarker() {
        readyMarker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "pgroll-ready-marker");
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

    private String env(Deployment deployment, String name) {
        return ContainerEnv.value(client, deployment, name);
    }
}
