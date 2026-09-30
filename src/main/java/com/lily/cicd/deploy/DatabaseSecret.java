package com.lily.cicd.deploy;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DB 모듈이 준 접속 정보를 슬롯별 Secret({@code {app}-{slot}-db})에 넣고, 컨테이너에는 secretKeyRef 로만 연결한다.
 * Deployment 스펙에 비밀번호가 평문으로 남지 않아서, 읽기 권한(ClusterRole view)만 있는 사람에게 보이지 않는다.
 *
 * <p>앱 하나에 Secret 하나가 아니라 슬롯마다 둔다. 롤백으로 이전 슬롯을 다시 띄울 때
 * 그 슬롯이 배포될 때의 값(예: {@code SPRING_FLYWAY_ENABLED})을 그대로 읽어야 하기 때문이다.
 */
final class DatabaseSecret {

    private DatabaseSecret() {
    }

    static String name(String deploymentName) {
        return deploymentName + "-db";
    }

    /** 요청 env 나 엔진 값이 같은 이름을 쓰면 그쪽이 이기므로 Secret 에서 뺀다 */
    static Map<String, String> entries(Map<String, String> databaseEnv, Set<String> overridden) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (databaseEnv == null) {
            return entries;
        }
        databaseEnv.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !overridden.contains(key)) {
                entries.put(key, value);
            }
        });
        return entries;
    }

    static void apply(KubernetesClient client, String namespace, String appName, String deploymentName,
                      Map<String, String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(name(deploymentName))
                    .withNamespace(namespace)
                    .addToLabels("app", appName)
                .endMetadata()
                .withType("Opaque")
                .withStringData(entries)
                .build();
        client.secrets().inNamespace(namespace).resource(secret).createOrReplace();
    }

    static List<EnvVar> refs(String deploymentName, Set<String> keys) {
        List<EnvVar> vars = new ArrayList<>();
        for (String key : keys) {
            vars.add(new EnvVarBuilder()
                    .withName(key)
                    .withNewValueFrom()
                        .withNewSecretKeyRef()
                            .withName(name(deploymentName))
                            .withKey(key)
                        .endSecretKeyRef()
                    .endValueFrom()
                    .build());
        }
        return vars;
    }
}
