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
 */
public final class NginxIngressRouter implements TrafficRouter {

    private static final String CANARY_SUFFIX = "-canary-ingress";

    private final KubernetesClient k8sClient;

    public NginxIngressRouter(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public void route(DeployContext context) {
        String name = mainName(context.appName());
        Ingress existing = k8sClient.network().v1().ingresses()
                .inNamespace(context.namespace())
                .withName(name)
                .get();

        Map<String, String> annotations = new LinkedHashMap<>();
        if (existing != null && existing.getMetadata() != null && existing.getMetadata().getAnnotations() != null) {
            annotations.putAll(existing.getMetadata().getAnnotations());
        }
        annotations.put("kubernetes.io/ingress.class", "nginx");

        List<IngressRule> rules = new ArrayList<>();
        if (existing != null && existing.getSpec() != null && existing.getSpec().getRules() != null) {
            for (IngressRule rule : existing.getSpec().getRules()) {
                if (rule.getHost() == null || !rule.getHost().equals(context.host())) {
                    rules.add(rule);
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

    /** CanaryDeploymentStrategy 가 직접 쓰던 가중치 Ingress 를 그대로 옮겼다 */
    @Override
    public void openCanary(DeployContext context, String canaryService, int weight) {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("kubernetes.io/ingress.class", "nginx");
        annotations.put("nginx.ingress.kubernetes.io/canary", "true");
        annotations.put("nginx.ingress.kubernetes.io/canary-weight", Integer.toString(weight));
        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                    .withName(canaryName(context.appName()))
                    .withNamespace(context.namespace())
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withIngressClassName("nginx")
                    .addNewRule()
                        .withHost(context.host())
                        .withNewHttp()
                            .addNewPath()
                                .withPath("/")
                                .withPathType("Prefix")
                                .withNewBackend()
                                    .withNewService()
                                        .withName(canaryService)
                                        .withNewPort().withNumber(context.servicePort()).endPort()
                                    .endService()
                                .endBackend()
                            .endPath()
                        .endHttp()
                    .endRule()
                .endSpec()
                .build();
        k8sClient.network().v1().ingresses().inNamespace(context.namespace()).resource(ingress).createOrReplace();
    }

    @Override
    public void closeCanary(String namespace, String appName) {
        k8sClient.network().v1().ingresses().inNamespace(namespace).withName(canaryName(appName)).delete();
    }

    /** CanaryAnalysis 가 판정 전에 보던 확인을 그대로 옮겼다: {@code {app}-ingress} 에 그 host 규칙이 있는지 */
    @Override
    public boolean routes(String namespace, String appName, String host) {
        Ingress main = k8sClient.network().v1().ingresses().inNamespace(namespace).withName(mainName(appName)).get();
        return main != null && main.getSpec() != null && main.getSpec().getRules() != null
                && main.getSpec().getRules().stream().anyMatch(rule -> host.equals(rule.getHost()));
    }

    /** DeployRecovery 가 직접 훑던 것을 옮겼다: 이름이 {@code -canary-ingress} 로 끝나는 Ingress */
    @Override
    public List<AppRef> openCanaries() {
        List<AppRef> apps = new ArrayList<>();
        for (Ingress ingress : k8sClient.network().v1().ingresses().inAnyNamespace().list().getItems()) {
            String name = ingress.getMetadata().getName();
            if (name.endsWith(CANARY_SUFFIX) && name.length() > CANARY_SUFFIX.length()) {
                apps.add(new AppRef(ingress.getMetadata().getNamespace(),
                        name.substring(0, name.length() - CANARY_SUFFIX.length())));
            }
        }
        return apps;
    }

    /** AppRemover 가 직접 지우던 두 Ingress 를 옮겼다. 트래픽 입구부터 끊는다 */
    @Override
    public List<String> remove(String namespace, String appName) {
        List<String> deleted = new ArrayList<>();
        for (String name : List.of(mainName(appName), canaryName(appName))) {
            if (!k8sClient.network().v1().ingresses().inNamespace(namespace).withName(name).delete().isEmpty()) {
                deleted.add("ingress/" + name);
            }
        }
        return deleted;
    }

    static String mainName(String appName) {
        return appName + "-ingress";
    }

    static String canaryName(String appName) {
        return appName + CANARY_SUFFIX;
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
