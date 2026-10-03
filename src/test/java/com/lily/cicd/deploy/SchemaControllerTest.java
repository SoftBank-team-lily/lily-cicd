package com.lily.cicd.deploy;

import com.lily.cicd.release.DeployLock;
import com.lily.cicd.release.ReleaseStore;
import com.lily.cicd.schema.SchemaHistory;
import com.lily.cicd.schema.SchemaOperationException;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 스키마 이력 패널 API. DB 이력 읽기는 mock 이다 (실제 SQL 은 SchemaHistoryPostgresTest) */
class SchemaControllerTest {

    private static final String NS = "default";
    private static final Map<String, String> DB_ENV = Map.of(
            "DB_URL", "jdbc:postgresql://db/lily", "DB_USERNAME", "lily", "DB_PASSWORD", "pw");

    private static KubernetesServer server;
    private KubernetesClient client;
    private SchemaHistory history;

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
        history = mock(SchemaHistory.class);
    }

    @Test
    void 앱이_없으면_404() {
        assertEquals(404, controller().schema("lily", null).getStatusCode().value());
    }

    @Test
    void DB_없는_앱은_DB_이력_없이_슬롯과_이유를_돌려준다() {
        givenSlot("stable", 2, "2026-10-03T01:00:00Z", null, null, null, null);

        SchemaController.SchemaView view = controller().schema("lily", null).getBody();

        assertNull(view.database());
        assertNull(view.engine());
        assertEquals(1, view.slots().size());
        assertTrue(view.history().isEmpty());
        assertEquals("DB 를 쓰지 않는 앱이다", view.message());
    }

    @Test
    void 롤백_창이_열린_pgroll_앱은_현재_버전과_창과_DB_이력을_돌려준다() {
        givenSlot("stable", 0, "2026-10-03T01:00:00Z", "01_create_posts", "postgres", "complete", null);
        givenSlot("canary", 2, "2026-10-03T02:00:00Z", "02_add_slug", "postgres", "active", "2026-10-03T02:10:00Z");
        List<SchemaHistory.Entry> entries = List.of(
                new SchemaHistory.Entry("01_create_posts", null, "complete", Instant.parse("2026-10-03T01:00:00Z")),
                new SchemaHistory.Entry("02_add_slug", null, "active", Instant.parse("2026-10-03T02:00:00Z")));
        when(history.read(any())).thenReturn(new SchemaHistory.History("pgroll", entries));

        ResponseEntity<SchemaController.SchemaView> response = controller().schema("lily", null);

        SchemaController.SchemaView view = response.getBody();
        assertEquals("pgroll", view.engine());
        assertEquals("02_add_slug", view.currentVersion());
        assertEquals("02_add_slug", view.window().migration());
        assertEquals(Instant.parse("2026-10-03T02:10:00Z"), view.window().completeAfter());
        assertEquals(entries, view.history());
        assertNull(view.message());
    }

    @Test
    void DB_이력을_읽지_못하면_이력만_비우고_이유를_담는다() {
        givenSlot("stable", 2, "2026-10-03T01:00:00Z", "3", "postgres", null, null);
        when(history.read(any())).thenThrow(new SchemaOperationException("connection refused", null));

        SchemaController.SchemaView view = controller().schema("lily", null).getBody();

        assertEquals("flyway", view.engine());
        assertEquals("3", view.currentVersion());
        assertTrue(view.history().isEmpty());
        assertEquals("DB 이력을 읽지 못했다", view.message());
    }

    private SchemaController controller() {
        ReleaseStore releases = new ReleaseStore(client);
        PgrollSchema pgroll = new PgrollSchema(mock(com.lily.cicd.schema.PgrollMigrator.class), context -> DB_ENV,
                releases, client, Duration.ofMinutes(10));
        PgrollCompleter completer = new PgrollCompleter(pgroll, context -> DB_ENV, releases, new DeployLock(client),
                Duration.ofSeconds(30));
        return new SchemaController(completer, releases, context -> DB_ENV, history);
    }

    private void givenSlot(String slot, int replicas, String deployedAt, String version, String database,
                           String state, String completeAfter) {
        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata()
                    .withName("lily-" + slot)
                    .withNamespace(NS)
                    .addToLabels("app", "lily")
                    .addToAnnotations(ReleaseStore.DEPLOYED_AT, deployedAt)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector().addToMatchLabels("app", "lily").endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", "lily").endMetadata()
                        .withNewSpec().addNewContainer().withName("lily").withImage("image").endContainer().endSpec()
                    .endTemplate()
                .endSpec();
        if (version != null) {
            builder.editMetadata().addToAnnotations(ReleaseStore.SCHEMA_VERSION, version).endMetadata();
        }
        if (database != null) {
            builder.editMetadata().addToAnnotations(ReleaseStore.DATABASE, database).endMetadata();
        }
        if (state != null) {
            builder.editMetadata().addToAnnotations(ReleaseStore.SCHEMA_ENGINE, "pgroll")
                    .addToAnnotations(ReleaseStore.PGROLL_STATE, state).endMetadata();
            if (completeAfter != null) {
                builder.editMetadata().addToAnnotations(ReleaseStore.PGROLL_COMPLETE_AFTER, completeAfter).endMetadata();
            }
        }
        client.apps().deployments().inNamespace(NS).resource(builder.build()).create();
    }
}
