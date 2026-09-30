package com.lily.cicd.module;

import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Router 모듈이 아직 없을 때 쓰는 구현.
 * Nginx Ingress 로 {@code {appName}-ingress} 를 Service 80 에 연결한다.
 */
public final class NginxIngressRouter implements TrafficRouter {

    private final KubernetesClient k8sClient;

    public NginxIngressRouter(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public void route(DeployContext context) {
        var ingress = new IngressBuilder()
                .withNewMetadata()
                    .withName(context.appName() + "-ingress")
                    .withNamespace(context.namespace())
                    .addToAnnotations("kubernetes.io/ingress.class", "nginx")
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
                                        .withName(context.serviceName())
                                        .withNewPort().withNumber(context.servicePort()).endPort()
                                    .endService()
                                .endBackend()
                            .endPath()
                        .endHttp()
                    .endRule()
                .endSpec()
                .build();
        k8sClient.network().v1().ingresses()
                .inNamespace(context.namespace())
                .resource(ingress)
                .createOrReplace();
    }
}
