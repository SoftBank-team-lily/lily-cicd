package com.lily.cicd.module;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nginx Ingress 로 {@code {appName}-ingress} 를 Service 80 에 연결한다.
 *
 * <p>이미 있는 Ingress 는 통째로 갈아끼우지 않는다. 이 배포의 호스트 규칙만 넣고,
 * 다른 호스트(로드밸런서에서 추가한 nip.io 등)와 TLS 는 그대로 둔다.
 *
 * <p>온프레미스로 넘겨 둔 앱({@link OnPremUpstream})을 다시 배포하면 클라우드로 돌아온 것이다.
 * 넘기던 주석을 빼고 다른 호스트의 백엔드도 이 Service 로 되돌린다.
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
        annotations = new LinkedHashMap<>(OnPremUpstream.withoutOnPrem(annotations));
        annotations.put("kubernetes.io/ingress.class", "nginx");

        List<IngressRule> rules = new ArrayList<>();
        if (existing != null && existing.getSpec() != null && existing.getSpec().getRules() != null) {
            for (IngressRule rule : existing.getSpec().getRules()) {
                if (rule.getHost() == null || !rule.getHost().equals(context.host())) {
                    rules.add(backToService(rule, context));
                }
            }
        }
        rules.add(rule(context));

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

    /** 온프레미스 ExternalName 을 보던 규칙은 이 배포의 Service 로 */
    private static IngressRule backToService(IngressRule rule, DeployContext context) {
        if (rule.getHttp() == null || rule.getHttp().getPaths() == null) {
            return rule;
        }
        rule.getHttp().getPaths().forEach(path -> {
            if (path.getBackend() != null && path.getBackend().getService() != null
                    && path.getBackend().getService().getName() != null
                    && path.getBackend().getService().getName().endsWith(OnPremUpstream.SERVICE_SUFFIX)) {
                path.getBackend().getService().setName(context.serviceName());
                path.getBackend().getService().getPort().setNumber(context.servicePort());
            }
        });
        return rule;
    }

    private static IngressRule rule(DeployContext context) {
        return new IngressRuleBuilder()
                .withHost(context.host())
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
