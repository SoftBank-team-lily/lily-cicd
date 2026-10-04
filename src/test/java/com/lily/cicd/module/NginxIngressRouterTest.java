package com.lily.cicd.module;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NginxIngressRouterTest {

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
        client.network().v1().ingresses().inNamespace("default").delete();
    }

    @Test
    void 다시_배포해도_다른_호스트는_남긴다() {
        client.network().v1().ingresses().inNamespace("default").resource(new IngressBuilder()
                .withNewMetadata().withName("blog-ingress").withNamespace("default").endMetadata()
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
                    .addNewTl().withHosts("blog.43.200.152.53.nip.io").withSecretName("extra-tls").endTl()
                .endSpec()
                .build()).create();

        new NginxIngressRouter(client).route(new DeployContext(
                "blog", "default", "image:2", 8080, 80, "blog.apps.lilycloud.kr", "2",
                "green", "blog-svc", "/actuator/prometheus"));

        Ingress ingress = client.network().v1().ingresses().inNamespace("default").withName("blog-ingress").get();
        List<String> hosts = ingress.getSpec().getRules().stream().map(rule -> rule.getHost()).toList();
        assertEquals(2, hosts.size());
        assertTrue(hosts.contains("blog.apps.lilycloud.kr"));
        assertTrue(hosts.contains("blog.43.200.152.53.nip.io"));
        assertEquals("blog-svc", ingress.getSpec().getRules().stream()
                .filter(rule -> "blog.apps.lilycloud.kr".equals(rule.getHost()))
                .findFirst().orElseThrow()
                .getHttp().getPaths().get(0).getBackend().getService().getName());
        assertEquals("extra-tls", ingress.getSpec().getTls().get(0).getSecretName());
        assertTrue(ingress.getMetadata().getAnnotations().containsKey("kubernetes.io/ingress.class"));
    }

    @Test
    void 별칭_호스트도_같은_Service_로_보낸다() {
        new NginxIngressRouter(client).route(new DeployContext(
                "shop", "default", "image:1", 8080, 80, "shop.lilycloud.kr", "1",
                "blue", "shop-svc", "/actuator/prometheus", null, List.of("shop-cloud.lilycloud.kr")));
        // 별칭 없이 다시 배포해도 별칭 규칙은 남는다
        new NginxIngressRouter(client).route(new DeployContext(
                "shop", "default", "image:2", 8080, 80, "shop.lilycloud.kr", "2",
                "green", "shop-svc", "/actuator/prometheus"));

        Ingress ingress = client.network().v1().ingresses().inNamespace("default").withName("shop-ingress").get();
        List<String> hosts = ingress.getSpec().getRules().stream().map(rule -> rule.getHost()).toList();
        assertEquals(2, hosts.size());
        assertTrue(hosts.containsAll(List.of("shop.lilycloud.kr", "shop-cloud.lilycloud.kr")));
        ingress.getSpec().getRules().forEach(rule -> assertEquals("shop-svc",
                rule.getHttp().getPaths().get(0).getBackend().getService().getName()));
    }

    @Test
    void canary_입구를_같은_호출로_비율만_바꾸고_닫는다() {
        NginxIngressRouter router = new NginxIngressRouter(client);
        DeployContext context = context("blog.lilycloud.kr");

        router.openCanary(context, "blog-canary-svc", 0);
        router.openCanary(context, "blog-canary-svc", 40);

        Ingress canary = client.network().v1().ingresses().inNamespace("default").withName("blog-canary-ingress").get();
        assertEquals("true", canary.getMetadata().getAnnotations().get("nginx.ingress.kubernetes.io/canary"));
        assertEquals("40", canary.getMetadata().getAnnotations().get("nginx.ingress.kubernetes.io/canary-weight"));
        assertEquals("blog.lilycloud.kr", canary.getSpec().getRules().get(0).getHost());
        assertEquals("blog-canary-svc", canary.getSpec().getRules().get(0).getHttp().getPaths().get(0)
                .getBackend().getService().getName());
        assertEquals(List.of(new TrafficRouter.AppRef("default", "blog")), router.openCanaries());

        router.closeCanary("default", "blog");
        router.closeCanary("default", "blog");

        assertNull(client.network().v1().ingresses().inNamespace("default").withName("blog-canary-ingress").get());
        assertEquals(List.of(), router.openCanaries());
    }

    @Test
    void routes_는_본_Ingress_에_그_호스트가_있는지_본다() {
        NginxIngressRouter router = new NginxIngressRouter(client);
        assertFalse(router.routes("default", "blog", "blog.lilycloud.kr"));

        router.route(context("blog.lilycloud.kr"));

        assertTrue(router.routes("default", "blog", "blog.lilycloud.kr"));
        assertFalse(router.routes("default", "blog", "other.lilycloud.kr"));
    }

    @Test
    void remove_는_본_Ingress_와_canary_를_지우고_지운_이름을_돌려준다() {
        NginxIngressRouter router = new NginxIngressRouter(client);
        router.route(context("blog.lilycloud.kr"));
        router.openCanary(context("blog.lilycloud.kr"), "blog-canary-svc", 10);

        assertEquals(List.of("ingress/blog-ingress", "ingress/blog-canary-ingress"), router.remove("default", "blog"));
        assertEquals(List.of(), router.remove("default", "blog"));
    }

    private static DeployContext context(String host) {
        return new DeployContext("blog", "default", "image:2", 8080, 80, host, "2",
                "green", "blog-svc", "/actuator/prometheus");
    }
}
