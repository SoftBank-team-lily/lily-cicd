package com.lily.cicd.deploy;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretKeySelector;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 테스트용: 컨테이너 env 를 읽는다. secretKeyRef 면 Secret 에서 값을 찾아 준다 */
final class ContainerEnv {

    private ContainerEnv() {
    }

    static EnvVar var(Deployment deployment, String name) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .filter(env -> name.equals(env.getName()))
                .findFirst()
                .orElse(null);
    }

    static String value(KubernetesClient client, Deployment deployment, String name) {
        EnvVar var = var(deployment, name);
        if (var == null) {
            return null;
        }
        if (var.getValueFrom() == null || var.getValueFrom().getSecretKeyRef() == null) {
            return var.getValue();
        }
        SecretKeySelector ref = var.getValueFrom().getSecretKeyRef();
        Secret secret = client.secrets().inNamespace(deployment.getMetadata().getNamespace())
                .withName(ref.getName()).get();
        // mock 서버는 stringData 를 data 로 옮기지 않을 수 있다
        if (secret.getStringData() != null && secret.getStringData().containsKey(ref.getKey())) {
            return secret.getStringData().get(ref.getKey());
        }
        return new String(Base64.getDecoder().decode(secret.getData().get(ref.getKey())), StandardCharsets.UTF_8);
    }
}
