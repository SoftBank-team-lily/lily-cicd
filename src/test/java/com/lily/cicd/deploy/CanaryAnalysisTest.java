package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployStages;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanaryAnalysisTest {

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
        client.services().inNamespace(NS).delete();
        client.network().v1().ingresses().inNamespace(NS).delete();
    }

    @Test
    void 판정은_클러스터_안에서만_하고_사용자_트래픽은_열지_않는다() {
        client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                .withNewMetadata().withName("lily-blue").withNamespace(NS).endMetadata()
                .withNewSpec().withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", "lily").endSelector()
                    .withNewTemplate().withNewMetadata().addToLabels("app", "lily").endMetadata()
                        .withNewSpec().addNewContainer().withName("lily").withImage("image:1").endContainer().endSpec()
                    .endTemplate()
                .endSpec()
                .build()).create();
        client.apps().deployments().inNamespace(NS).withName("lily-blue")
                .editStatus(current -> new DeploymentBuilder(current)
                        .withNewStatus().withReplicas(1).withReadyReplicas(1).endStatus().build());
        client.network().v1().ingresses().inNamespace(NS).resource(new IngressBuilder()
                .withNewMetadata().withName("lily-ingress").withNamespace(NS).endMetadata()
                .withNewSpec().withIngressClassName("nginx")
                    .addNewRule().withHost("lily.domain.com").withNewHttp()
                        .addNewPath().withPath("/").withPathType("Prefix")
                            .withNewBackend().withNewService().withName("lily-svc")
                                .withNewPort().withNumber(80).endPort()
                            .endService().endBackend()
                        .endPath()
                    .endHttp().endRule()
                .endSpec()
                .build()).create();

        DeployProperties properties = new DeployProperties();
        properties.getCanaryAnalysis().setDurationSeconds(1);
        properties.getCanaryAnalysis().setIntervalMillis(1000);
        properties.getCanaryAnalysis().setRequestTimeoutMillis(200);
        properties.getCanaryAnalysis().setMinSamples(20);
        List<String> stages = new ArrayList<>();
        DeployContext context = new DeployContext("lily", NS, "image:2", 8080, 80,
                "lily.domain.com", "2", "green", "lily-svc", "/actuator/prometheus");

        new CanaryAnalysis(client, properties).judge(context, "/whoami", new SlotPlan("green", "blue"),
                (stage, line) -> stages.add(stage + " " + line));

        assertTrue(stages.stream().anyMatch(line -> line.startsWith(DeployStages.CANARY_TRAFFIC)
                && line.contains("probe only")));
        assertNull(client.network().v1().ingresses().inNamespace(NS).withName("lily-canary-ingress").get());
        assertTrue(client.services().inNamespace(NS).withName("lily-canary-svc").get() != null);

        List<String> logs = new ArrayList<>();
        new CanaryAnalysis(client, properties).cleanup(NS, "lily", logs);
        assertNull(client.services().inNamespace(NS).withName("lily-canary-svc").get());
    }

    @Test
    void 카나리_슬롯은_track_레이블로_판정_서비스를_만든다() {
        client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                .withNewMetadata().withName("lily-stable").withNamespace(NS).endMetadata()
                .withNewSpec().withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", "lily").addToMatchLabels("track", "stable").endSelector()
                    .withNewTemplate().withNewMetadata().addToLabels("app", "lily").addToLabels("track", "stable").endMetadata()
                        .withNewSpec().addNewContainer().withName("lily").withImage("image:1").endContainer().endSpec()
                    .endTemplate()
                .endSpec()
                .build()).create();
        client.apps().deployments().inNamespace(NS).resource(new DeploymentBuilder()
                .withNewMetadata().withName("lily-canary").withNamespace(NS).endMetadata()
                .withNewSpec().withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", "lily").addToMatchLabels("track", "canary").endSelector()
                    .withNewTemplate().withNewMetadata().addToLabels("app", "lily").addToLabels("track", "canary").endMetadata()
                        .withNewSpec().addNewContainer().withName("lily").withImage("image:2").endContainer().endSpec()
                    .endTemplate()
                .endSpec()
                .build()).create();
        client.apps().deployments().inNamespace(NS).withName("lily-stable")
                .editStatus(current -> new DeploymentBuilder(current)
                        .withNewStatus().withReplicas(1).withReadyReplicas(1).endStatus().build());
        client.network().v1().ingresses().inNamespace(NS).resource(new IngressBuilder()
                .withNewMetadata().withName("lily-ingress").withNamespace(NS).endMetadata()
                .withNewSpec().withIngressClassName("nginx")
                    .addNewRule().withHost("lily.domain.com").withNewHttp()
                        .addNewPath().withPath("/").withPathType("Prefix")
                            .withNewBackend().withNewService().withName("lily-svc")
                                .withNewPort().withNumber(80).endPort()
                            .endService().endBackend()
                        .endPath()
                    .endHttp().endRule()
                .endSpec()
                .build()).create();

        DeployProperties properties = new DeployProperties();
        properties.getCanaryAnalysis().setDurationSeconds(1);
        properties.getCanaryAnalysis().setIntervalMillis(1000);
        properties.getCanaryAnalysis().setRequestTimeoutMillis(200);
        properties.getCanaryAnalysis().setMinSamples(20);
        DeployContext context = new DeployContext("lily", NS, "image:2", 8080, 80,
                "lily.domain.com", "2", "canary", "lily-svc", "/actuator/prometheus");

        new CanaryAnalysis(client, properties).judge(context, "/", new SlotPlan("canary", "stable"),
                (stage, line) -> { });

        assertEquals(Map.of("app", "lily", "track", "canary"),
                client.services().inNamespace(NS).withName("lily-canary-svc").get().getSpec().getSelector());
    }
}
