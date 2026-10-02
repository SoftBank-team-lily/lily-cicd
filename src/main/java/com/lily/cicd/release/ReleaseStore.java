package com.lily.cicd.release;

import com.lily.cicd.schema.MigrationSet;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 슬롯마다 어떤 릴리스가 올라가 있는지 클러스터에 남긴다. lily-cicd 는 AWS 권한이 없어서 DynamoDB 대신 여기에 둔다.
 *
 * <ul>
 *   <li>Deployment {@code {app}-{slot}} 어노테이션: 배포 시각, 스키마 버전, DB 엔진</li>
 *   <li>ConfigMap {@code {app}-{slot}-schema}: 그 릴리스의 마이그레이션 파일 전체. 롤백에 필요한 U 는 현재 릴리스에만 있다</li>
 * </ul>
 */
public class ReleaseStore {

    public static final String DEPLOYED_AT = "lily.io/deployed-at";
    public static final String SCHEMA_VERSION = "lily.io/schema-version";
    public static final String DATABASE = "lily.io/database";
    /** {@code pgroll} 이면 schema-version 은 pgroll 마이그레이션 이름이다. 없으면 Flyway 버전 */
    public static final String SCHEMA_ENGINE = "lily.io/schema-engine";
    /** 이 릴리스가 시작한 pgroll 마이그레이션의 상태: active(롤백 창) / complete / rolled-back */
    public static final String PGROLL_STATE = "lily.io/pgroll-state";
    /** 이 시각이 지나면 PgrollCompleter 가 complete 한다 */
    public static final String PGROLL_COMPLETE_AFTER = "lily.io/pgroll-complete-after";
    public static final String PGROLL = "pgroll";
    private static final Logger log = LoggerFactory.getLogger(ReleaseStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KubernetesClient k8s;

    public ReleaseStore(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    /** 스크립트가 없으면 예전 릴리스의 ConfigMap 을 지운다 (다른 릴리스의 U 로 롤백하지 않게) */
    public void saveScripts(String namespace, String appName, String slot, MigrationSet scripts) {
        String name = configMapName(appName, slot);
        if (scripts.isEmpty()) {
            k8s.configMaps().inNamespace(namespace).withName(name).delete();
            return;
        }
        k8s.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels("app", appName)
                    .addToLabels("lily.io/slot", slot)
                .endMetadata()
                .withData(scripts.files())
                .build()).createOrReplace();
    }

    public MigrationSet loadScripts(String namespace, String appName, String slot) {
        var configMap = k8s.configMaps().inNamespace(namespace).withName(configMapName(appName, slot)).get();
        return configMap == null ? MigrationSet.empty() : MigrationSet.parse(configMap.getData());
    }

    /**
     * 슬롯 Deployment 의 metadata 에만 붙인다. Pod template 은 바뀌지 않으므로 재시작이 없다.
     * {@code {app}-{slot}} 이름을 쓰지 않는 전략이면 기록 없이 넘어간다 (그 앱은 롤백을 거부한다).
     *
     * @param schemaVersion 플랫폼이 스키마를 맡지 않는 앱이면 null
     */
    public void annotate(String namespace, String appName, String slot, Instant deployedAt,
                         String schemaVersion, String database) {
        annotate(namespace, appName, slot, deployedAt, schemaVersion, database, null);
    }

    /**
     * @param schemaEngine {@code pgroll} 이면 schemaVersion 은 pgroll 마이그레이션 이름. Flyway 면 null
     */
    public void annotate(String namespace, String appName, String slot, Instant deployedAt,
                         String schemaVersion, String database, String schemaEngine) {
        Map<String, String> annotations = new HashMap<>();
        annotations.put(DEPLOYED_AT, deployedAt.toString());
        if (schemaVersion != null) {
            annotations.put(SCHEMA_VERSION, schemaVersion);
        }
        if (database != null && !database.isBlank()) {
            annotations.put(DATABASE, database);
        }
        // 슬롯을 다시 쓰는 배포라 이전 릴리스의 pgroll 기록이 남지 않게 지운다 (merge patch 의 null 은 삭제)
        annotations.put(SCHEMA_ENGINE, schemaEngine);
        annotations.put(PGROLL_STATE, null);
        annotations.put(PGROLL_COMPLETE_AFTER, null);
        patch(namespace, appName, slot, annotations);
    }

    /**
     * pgroll 상태만 고친다. 값이 null 인 키는 지운다.
     */
    public void markPgroll(String namespace, String appName, String slot, String state, Instant completeAfter) {
        Map<String, String> annotations = new HashMap<>();
        annotations.put(PGROLL_STATE, state);
        annotations.put(PGROLL_COMPLETE_AFTER, completeAfter == null ? null : completeAfter.toString());
        patch(namespace, appName, slot, annotations);
    }

    /** 이 앱에서 pgroll 기록이 있는 슬롯 Deployment 전부 (모든 namespace) */
    public List<Deployment> pgrollSlots() {
        return k8s.apps().deployments().inAnyNamespace().list().getItems().stream()
                .filter(d -> d.getMetadata().getAnnotations() != null
                        && PGROLL.equals(d.getMetadata().getAnnotations().get(SCHEMA_ENGINE)))
                .toList();
    }

    private void patch(String namespace, String appName, String slot, Map<String, String> annotations) {
        try {
            // resourceVersion 없는 merge patch. 컨트롤러가 status 를 계속 고쳐도 충돌하지 않는다.
            // 값이 null 인 키는 지워야 해서 null 을 그대로 쓰는 ObjectMapper 로 직렬화한다
            String body = JSON.writeValueAsString(Map.of("metadata", Map.of("annotations", annotations)));
            k8s.apps().deployments().inNamespace(namespace).withName(deploymentName(appName, slot))
                    .patch(PatchContext.of(PatchType.JSON_MERGE), body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("annotation 직렬화 실패", e);
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            log.warn("slot deployment absent, release not recorded. name={}", deploymentName(appName, slot));
        }
    }

    public Optional<Release> read(String namespace, String appName, String slot) {
        Deployment d = k8s.apps().deployments().inNamespace(namespace).withName(deploymentName(appName, slot)).get();
        return Optional.ofNullable(d).map(deployment -> toRelease(slot, deployment));
    }

    public static String deploymentName(String appName, String slot) {
        return appName + "-" + slot;
    }

    static String configMapName(String appName, String slot) {
        return appName + "-" + slot + "-schema";
    }

    private static Release toRelease(String slot, Deployment d) {
        Map<String, String> annotations = d.getMetadata().getAnnotations() == null
                ? Map.of() : d.getMetadata().getAnnotations();
        List<Container> containers = d.getSpec().getTemplate().getSpec().getContainers();
        return new Release(
                slot,
                parseInstant(annotations.get(DEPLOYED_AT)),
                annotations.get(SCHEMA_VERSION),
                annotations.get(DATABASE),
                d.getSpec().getReplicas() == null ? 0 : d.getSpec().getReplicas(),
                containers.isEmpty() ? null : containers.get(0).getImage(),
                annotations.get(SCHEMA_ENGINE),
                annotations.get(PGROLL_STATE),
                parseInstant(annotations.get(PGROLL_COMPLETE_AFTER)));
    }

    public static Release toRelease(Deployment d) {
        String name = d.getMetadata().getName();
        String app = d.getMetadata().getLabels() == null ? null : d.getMetadata().getLabels().get("app");
        String slot = app != null && name.startsWith(app + "-") ? name.substring(app.length() + 1) : name;
        return toRelease(slot, d);
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 슬롯 하나에 올라간 릴리스.
     *
     * @param deployedAt    이 기능 이전에 만든 슬롯이면 null
     * @param schemaVersion 이 릴리스가 끝났을 때의 스키마 버전. 플랫폼이 스키마를 맡지 않으면 null
     */
    public record Release(String slot, Instant deployedAt, String schemaVersion, String database,
                          int replicas, String image, String schemaEngine, String pgrollState,
                          Instant pgrollCompleteAfter) {

        public Release(String slot, Instant deployedAt, String schemaVersion, String database,
                       int replicas, String image) {
            this(slot, deployedAt, schemaVersion, database, replicas, image, null, null, null);
        }

        public boolean schemaManaged() {
            return schemaVersion != null;
        }

        /** schemaVersion 이 pgroll 마이그레이션 이름이다 */
        public boolean pgroll() {
            return PGROLL.equals(schemaEngine);
        }
    }
}
