package com.lily.cicd.module;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nginx Ingress 로 {@code {appName}-ingress} 를 Service 80 에 연결한다.
 *
 * <p>이미 있는 Ingress 는 통째로 갈아끼우지 않는다. 이 배포의 호스트 규칙만 넣고,
 * 다른 호스트(로드밸런서에서 추가한 nip.io 등)와 TLS 는 그대로 둔다.
 * 추가 호스트({@link DeployContext#aliases()})도 같은 Service 로 넣는다. 이번 배포에 없는 별칭 규칙은 지우지 않는다.
 */
public final class NginxIngressRouter implements TrafficRouter {

    private final KubernetesClient k8sClient;

    public NginxIngressRouter(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public void route(DeployContext context) {
        String name = context.appName() + "-ingress";
        Ingress existing = k8sClient.network().v1().ingresses()
                .inNamespace(context.namespace())
                .withName(name)
                .get();

        Map<String, String> annotations = new LinkedHashMap<>();
        if (existing != null && existing.getMetadata() != null && existing.getMetadata().getAnnotations() != null) {
            annotations.putAll(existing.getMetadata().getAnnotations());
        }
        annotations.put("kubernetes.io/ingress.class", "nginx");

        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(context.host());
        hosts.addAll(context.aliases());
        List<IngressRule> rules = new ArrayList<>();
        if (existing != null && existing.getSpec() != null && existing.getSpec().getRules() != null) {
            for (IngressRule rule : existing.getSpec().getRules()) {
                if (rule.getHost() == null || !hosts.contains(rule.getHost())) {
                    rules.add(rule);
                }
            }
        }
        for (String host : hosts) {
            rules.add(rule(host, context));
        }

        List<IngressTLS> tls = existing != null && existing.getSpec() != null && existing.getSpec().getTls() != null
                ? existing.getSpec().getTls() : List.of();

        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(context.namespace())
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withIngressClassName("nginx")
                    .withRules(rules)
                    .withTls(tls)
                .endSpec()
                .build();
        k8sClient.network().v1().ingresses()
                .inNamespace(context.namespace())
                .resource(ingress)
                .createOrReplace();
    }

    private static IngressRule rule(String host, DeployContext context) {
        return new IngressRuleBuilder()
                .withHost(host)
                .withNewHttp()
                    .addNewPath()
                        .withPath("/")
                        .withPathType("Prefix")
                        .withNewBackend()
                            .withNewService()
                                .withName(context.serviceName())
                                .withNewPort().withNumber(context.servicePort()).endPort()
                            .endService()
                        .endBackend()
                    .endPath()
                .endHttp()
                .build();
    }
}
