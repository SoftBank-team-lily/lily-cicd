package com.lily.cicd.module;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnPremUpstreamTest {

    private static final String HOST = "blog-1a2b3c.lilycloud.kr";
    private static KubernetesServer server;
    private KubernetesClient client;
    private OnPremUpstream upstream;

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
        client.network().v1().ingresses().inNamespace("default").delete();
        client.services().inNamespace("default").delete();
        client.secrets().inNamespace("default").delete();
        upstream = new OnPremUpstream(client);
        client.network().v1().ingresses().inNamespace("default").resource(new IngressBuilder()
                .withNewMetadata().withName("blog-ingress").withNamespace("default")
                    .addToAnnotations("kubernetes.io/ingress.class", "nginx").endMetadata()
                .withNewSpec()
                    .withIngressClassName("nginx")
                    .addNewRule().withHost("blog.apps.lilycloud.kr").withNewHttp()
                        .addNewPath().withPath("/").withPathType("Prefix")
                            .withNewBackend().withNewService().withName("blog-svc")
                                .withNewPort().withNumber(80).endPort()
                            .endService().endBackend()
                        .endPath()
                    .endHttp().endRule()
                    .addNewRule().withHost("blog.43.200.152.53.nip.io").withNewHttp()
                        .addNewPath().withPath("/").withPathType("Prefix")
                            .withNewBackend().withNewService().withName("blog-svc")
                                .withNewPort().withNumber(80).endPort()
                            .endService().endBackend()
                        .endPath()
                    .endHttp().endRule()
                .endSpec()
                .build()).create();
    }

    @Test
    void 모든_호스트를_온프레미스_주소로_넘기고_SNI_를_보낸다() {
        upstream.point("default", "blog", HOST);

        Ingress ingress = ingress();
        assertEquals(List.of("blog-onprem", "blog-onprem"), backends(ingress));
        Map<String, String> annotations = ingress.getMetadata().getAnnotations();
        assertEquals("HTTPS", annotations.get("nginx.ingress.kubernetes.io/backend-protocol"));
        assertEquals(HOST, annotations.get("nginx.ingress.kubernetes.io/upstream-vhost"));
        assertEquals(HOST, annotations.get("nginx.ingress.kubernetes.io/proxy-ssl-name"));
        assertEquals("default/lily-public-ca", annotations.get("nginx.ingress.kubernetes.io/proxy-ssl-secret"));
        assertEquals("nginx", annotations.get("kubernetes.io/ingress.class"));

        Service external = client.services().inNamespace("default").withName("blog-onprem").get();
        assertEquals("ExternalName", external.getSpec().getType());
        assertEquals(HOST, external.getSpec().getExternalName());
        assertNotNull(client.secrets().inNamespace("default").withName("lily-public-ca").get());
        assertEquals(Optional.of(HOST), upstream.current("default", "blog"));
    }

    @Test
    void 되돌리면_클러스터_Service_와_원래_주석만_남는다() {
        upstream.point("default", "blog", HOST);

        assertTrue(upstream.restore("default", "blog"));

        Ingress ingress = ingress();
        assertEquals(List.of("blog-svc", "blog-svc"), backends(ingress));
        assertEquals(Map.of("kubernetes.io/ingress.class", "nginx"), ingress.getMetadata().getAnnotations());
        assertNull(client.services().inNamespace("default").withName("blog-onprem").get());
        assertEquals(Optional.empty(), upstream.current("default", "blog"));
        assertFalse(upstream.restore("default", "blog"));
    }

    @Test
    void 다시_배포하면_클라우드로_돌아온다() {
        upstream.point("default", "blog", HOST);

        new NginxIngressRouter(client).route(new DeployContext(
                "blog", "default", "image:2", 8080, 80, "blog.apps.lilycloud.kr", "2",
                "green", "blog-svc", "/actuator/prometheus"));

        Ingress ingress = ingress();
        assertEquals(List.of("blog-svc", "blog-svc"), backends(ingress));
        assertFalse(ingress.getMetadata().getAnnotations().containsKey("nginx.ingress.kubernetes.io/upstream-vhost"));
        assertFalse(ingress.getMetadata().getAnnotations().containsKey("nginx.ingress.kubernetes.io/backend-protocol"));
    }

    @Test
    void 호스트가_아니거나_Ingress_가_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> upstream.point("default", "blog", "https://x.lilycloud.kr/"));
        assertThrows(IllegalArgumentException.class, () -> upstream.point("default", "blog", "10"));
        assertThrows(IllegalStateException.class, () -> upstream.point("default", "nothing", HOST));
    }

    @Test
    void CA_묶음은_PEM_인증서다() {
        String pem = OnPremUpstream.caBundle();
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"));
        assertTrue(pem.split("BEGIN CERTIFICATE").length > 20);
    }

    private Ingress ingress() {
        return client.network().v1().ingresses().inNamespace("default").withName("blog-ingress").get();
    }

    private static List<String> backends(Ingress ingress) {
        return ingress.getSpec().getRules().stream()
                .flatMap(rule -> rule.getHttp().getPaths().stream())
                .map(path -> path.getBackend().getService().getName())
                .toList();
    }
}
