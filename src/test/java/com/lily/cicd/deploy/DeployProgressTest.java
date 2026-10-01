package com.lily.cicd.deploy;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployProgressTest {

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
    }

    @Test
    void 진행_상태는_프로세스가_죽어도_ConfigMap에_남는다() {
        DeployProgress progress = new DeployProgress(client);
        progress.update(NS, "blog", "deployment", "waiting", "img:1");

        DeployProgress restarted = new DeployProgress(client);
        DeployProgress.Snapshot loaded = restarted.get(NS, "blog").orElseThrow();

        assertEquals("deployment", loaded.stage());
        assertEquals("waiting", loaded.detail());
        assertEquals("img:1", loaded.image());
        assertEquals("lily-progress-blog", client.configMaps().inNamespace(NS).withName("lily-progress-blog")
                .get().getMetadata().getName());
    }

    @Test
    void 재시작된_프로세스는_죽은_배포의_시각을_갱신하지_않는다() throws Exception {
        DeployProgress progress = new DeployProgress(client);
        progress.update(NS, "blog", "deployment", "waiting", "img:1");
        Instant written = progress.get(NS, "blog").orElseThrow().updatedAt();

        Thread.sleep(5);
        DeployProgress restarted = new DeployProgress(client);
        restarted.touch(NS, "blog");

        assertEquals(written, restarted.get(NS, "blog").orElseThrow().updatedAt());
    }

    @Test
    void 끝난_단계는_맥박으로_시각이_바뀌지_않는다() throws Exception {
        DeployProgress progress = new DeployProgress(client);
        progress.update(NS, "blog", "succeeded", "done", "img:1");
        Instant done = progress.get(NS, "blog").orElseThrow().updatedAt();

        Thread.sleep(5);
        progress.touch(NS, "blog");

        assertEquals(done, progress.get(NS, "blog").orElseThrow().updatedAt());
        assertTrue(progress.get(NS, "other").isEmpty());
        assertFalse(client.configMaps().inNamespace(NS).list().getItems().isEmpty());
    }
}
