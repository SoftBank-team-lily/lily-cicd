package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.release.DeployConflictException;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * blue 와 green 두 슬롯 중 트래픽이 없는 쪽에 새 이미지를 올리고,
 * Ready 이후에 Service selector 의 color 를 한 번에 바꾼다.
 * 이전 슬롯은 지우지 않고 replica 를 0 으로 줄인다.
 */
public class BlueGreenDeploymentStrategy implements DeploymentStrategy {

    private static final Logger log = LoggerFactory.getLogger(BlueGreenDeploymentStrategy.class);
    private static final int REPLICAS = 1;
    private static final int SERVICE_PORT = 80;
    private static final String COLOR_BLUE = "blue";
    private static final String COLOR_GREEN = "green";

    private final KubernetesClient k8sClient;
    private final DeployProperties properties;

    public BlueGreenDeploymentStrategy(KubernetesClient k8sClient, DeployProperties properties) {
        this.k8sClient = k8sClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "blue-green";
    }

    /**
     * Service selector 의 color 가 지금 트래픽을 받는 색이다.
     * Service 가 없거나 color 가 green 이면 다음은 blue. color 가 blue 이면 다음은 green.
     */
    @Override
    public SlotPlan plan(String namespace, String appName, List<String> logs) {
        String serviceName = serviceName(appName);
        Service service;
        try {
            service = k8sClient.services().inNamespace(namespace).withName(serviceName).get();
        } catch (KubernetesClientException e) {
            log.error("failed to read service. namespace={} name={} code={} message={}",
                    namespace, serviceName, e.getCode(), e.getMessage(), e);
            throw new DeploymentFailedException(
                    "active color 조회 실패: " + serviceName, logs, e);
        }

        String color = null;
        if (service != null && service.getSpec() != null && service.getSpec().getSelector() != null) {
            color = service.getSpec().getSelector().get("color");
        }

        if (service == null || color == null || COLOR_GREEN.equals(color)) {
            logs.add("step1: service=" + (service == null ? "absent" : serviceName)
                    + " color=" + color + " -> target=blue current=green");
            return new SlotPlan(COLOR_BLUE, COLOR_GREEN);
        }
        if (COLOR_BLUE.equals(color)) {
            logs.add("step1: service=" + serviceName + " color=blue -> target=green current=blue");
            return new SlotPlan(COLOR_GREEN, COLOR_BLUE);
        }

        logs.add("step1: unsupported color=" + color);
        throw new DeploymentFailedException(
                "selector color 는 blue 또는 green 만 지원한다. 현재 값: " + color, logs, null);
    }

    @Override
    public void applyTarget(
            DeployCommand command,
            String namespace,
            SlotPlan plan,
            Map<String, String> databaseEnv,
            List<String> logs) {
        String name = deploymentName(command.appName(), plan.target());
        Deployment deployment = buildDeployment(command, namespace, plan.target(), databaseEnv);
        try {
            // Fabric8 6.13 은 server-side apply 를 권장하며 createOrReplace 를 deprecated 로 표시한다.
            // 이 모듈의 계약이 createOrReplace 이므로 호출은 유지한다.
            k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
            logs.add("step2: applied deployment " + name + " image=" + command.imageUrl());
        } catch (KubernetesClientException e) {
            log.error("failed to apply deployment. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("step2: apply failed " + name + " — service 는 변경하지 않음");
            throw new DeploymentFailedException(
                    "target deployment 적용 실패: " + name, logs, e);
        }
    }

    /**
     * Ready 가 되기 전에는 Service selector 를 바꾸지 않는다.
     * 타임아웃이면 target Deployment 만 지우고 파이프라인을 중단한다.
     */
    @Override
    public void awaitReady(String namespace, String appName, SlotPlan plan, List<String> logs) {
        String targetName = deploymentName(appName, plan.target());
        long timeout = properties.getReadinessTimeoutSeconds();
        try {
            Deployment ready = k8sClient.apps().deployments()
                    .inNamespace(namespace)
                    .withName(targetName)
                    .waitUntilReady(timeout, TimeUnit.SECONDS);
            if (ready == null) {
                throw new KubernetesClientTimeoutException(
                        "Deployment", targetName, namespace, timeout, TimeUnit.SECONDS);
            }
            logs.add("step3: deployment ready " + targetName);
        } catch (KubernetesClientException e) {
            log.error("readiness wait failed. namespace={} name={} timeoutSeconds={} code={} message={}",
                    namespace, targetName, timeout, e.getCode(), e.getMessage(), e);
            deleteTargetQuietly(namespace, targetName, logs);
            logs.add("step3: aborted. active service was not modified");
            throw new DeploymentFailedException(
                    "target deployment 가 " + timeout + "초 안에 Ready 가 되지 않음: " + targetName,
                    logs, e);
        }
    }

    @Override
    public void switchTraffic(DeployCommand command, String namespace, SlotPlan plan, List<String> logs) {
        String name = serviceName(command.appName());
        int targetPort = command.targetPort();
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels("app", command.appName())
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", command.appName(), "color", plan.target()))
                    .addNewPort()
                        .withName("http")
                        .withPort(SERVICE_PORT)
                        .withTargetPort(new IntOrString(targetPort))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
        try {
            k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
            logs.add("step4: service " + name + " selector color=" + plan.target()
                    + " port " + SERVICE_PORT + " -> " + targetPort);
        } catch (KubernetesClientException e) {
            log.error("failed to switch service. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("step4: service switch failed. 새 Deployment 는 남겨 둠. selector 는 바뀌지 않았을 수 있음");
            throw new DeploymentFailedException("service selector 전환 실패: " + name, logs, e);
        }
    }

    @Override
    public void retirePrevious(String namespace, String appName, SlotPlan plan, List<String> logs) {
        String name = deploymentName(appName, plan.previous());
        try {
            Deployment existing = k8sClient.apps().deployments().inNamespace(namespace).withName(name).get();
            if (existing == null) {
                logs.add("step6: previous deployment absent, skip scale down " + name);
                return;
            }
            k8sClient.apps().deployments().inNamespace(namespace).withName(name).scale(0);
            logs.add("step6: scaled to 0 " + name);
        } catch (KubernetesClientException e) {
            log.error("failed to scale down. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("step6: scale down failed. traffic 은 이미 새 color 로 전환된 상태");
            throw new DeploymentFailedException(
                    "이전 deployment scale down 실패: " + name + ". 트래픽은 새 color 를 보고 있다",
                    logs, e);
        }
    }

    /**
     * 지금 selector 색의 반대 슬롯이 되살릴 대상이다. replica 0 으로 남겨 둔 Deployment 가 있어야 한다.
     */
    @Override
    public SlotPlan planRollback(String namespace, String appName, List<String> logs) {
        Service service = k8sClient.services().inNamespace(namespace).withName(serviceName(appName)).get();
        String color = service == null || service.getSpec() == null || service.getSpec().getSelector() == null
                ? null : service.getSpec().getSelector().get("color");
        if (!COLOR_BLUE.equals(color) && !COLOR_GREEN.equals(color)) {
            throw new DeployConflictException(appName + " 의 blue-green Service 가 없다 (color=" + color + ")");
        }
        String previous = COLOR_BLUE.equals(color) ? COLOR_GREEN : COLOR_BLUE;
        if (k8sClient.apps().deployments().inNamespace(namespace).withName(deploymentName(appName, previous)).get() == null) {
            throw new DeployConflictException("되살릴 이전 슬롯이 없다: " + deploymentName(appName, previous));
        }
        logs.add("rollback: active=" + color + " -> restore=" + previous);
        return new SlotPlan(previous, color);
    }

    /**
     * 이전 슬롯을 replica 1 로 올리고 Ready 를 기다린 뒤 selector 의 color 만 바꾼다.
     * Ready 가 되지 않으면 다시 0 으로 내리고 트래픽은 건드리지 않는다.
     */
    @Override
    public void restorePrevious(String namespace, String appName, SlotPlan plan, List<String> logs) {
        String name = deploymentName(appName, plan.target());
        long timeout = properties.getReadinessTimeoutSeconds();
        var deployment = k8sClient.apps().deployments().inNamespace(namespace).withName(name);
        deployment.scale(REPLICAS);
        logs.add("rollback: scaled " + name + " replicas=" + REPLICAS);
        try {
            if (deployment.waitUntilReady(timeout, TimeUnit.SECONDS) == null) {
                throw new KubernetesClientTimeoutException("Deployment", name, namespace, timeout, TimeUnit.SECONDS);
            }
        } catch (KubernetesClientException e) {
            log.error("rollback readiness failed. namespace={} name={} message={}", namespace, name, e.getMessage(), e);
            deployment.scale(0);
            logs.add("rollback: " + name + " not ready in " + timeout + "s, scaled back to 0. traffic unchanged");
            throw new DeploymentFailedException("이전 슬롯이 " + timeout + "초 안에 Ready 가 되지 않음: " + name, logs, e);
        }
        logs.add("rollback: deployment ready " + name);
        k8sClient.services().inNamespace(namespace).withName(serviceName(appName))
                .patch(PatchContext.of(PatchType.JSON_MERGE),
                        Serialization.asJson(Map.of("spec", Map.of("selector", Map.of("color", plan.target())))));
        logs.add("rollback: service " + serviceName(appName) + " selector color=" + plan.target());
    }

    private Deployment buildDeployment(
            DeployCommand command,
            String namespace,
            String targetColor,
            Map<String, String> databaseEnv) {
        String appName = command.appName();
        String name = deploymentName(appName, targetColor);
        Map<String, String> labels = Map.of("app", appName, "color", targetColor);
        String readinessPath = firstNonBlank(command.readinessPath(), properties.getReadinessPath());
        String livenessPath = firstNonBlank(command.livenessPath(), properties.getLivenessPath());

        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(REPLICAS)
                    .withNewSelector()
                        .addToMatchLabels(labels)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .withTerminationGracePeriodSeconds(30L)
                            .addNewContainer()
                                .withName(appName)
                                .withImage(command.imageUrl())
                                .withImagePullPolicy("IfNotPresent")
                                .addNewPort()
                                    .withName("http")
                                    .withContainerPort(command.targetPort())
                                .endPort()
                                .withEnv(containerEnv(command, targetColor, databaseEnv))
                                .withReadinessProbe(httpProbe(readinessPath, command.targetPort()))
                                .withLivenessProbe(httpProbe(livenessPath, command.targetPort()))
                                .withStartupProbe(startupProbe(livenessPath, command.targetPort()))
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec();

        if (command.imagePullSecret() != null && !command.imagePullSecret().isBlank()) {
            builder.editSpec()
                    .editTemplate()
                    .editSpec()
                    .addNewImagePullSecret().withName(command.imagePullSecret()).endImagePullSecret()
                    .endSpec()
                    .endTemplate()
                    .endSpec();
        }
        return builder.build();
    }

    /**
     * lily-blog-sample 이 {@code /version}, {@code /whoami} 에 이 값을 그대로 내려준다.
     * {@code APP_COLOR} 는 실제 슬롯과 같아야 하므로 extraEnv 보다 나중에 덮어쓴다.
     */
    private List<EnvVar> containerEnv(
            DeployCommand command, String targetColor, Map<String, String> databaseEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        putAll(env, databaseEnv);
        putAll(env, command.extraEnv());
        env.put("APP_VERSION", firstNonBlank(command.appVersion(), "dev"));
        env.put("SERVER_PORT", Integer.toString(command.targetPort()));
        env.put("APP_COLOR", targetColor);

        List<EnvVar> vars = new ArrayList<>();
        env.forEach((key, value) -> vars.add(new EnvVarBuilder().withName(key).withValue(value).build()));
        return vars;
    }

    private static void putAll(Map<String, String> target, Map<String, String> source) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                target.put(key, value);
            }
        });
    }

    /**
     * 기동이 끝날 때까지 liveness 를 미룬다. Spring Boot 앱은 t3.medium 에서 기동에 10~20초가 걸려서,
     * liveness(5초 후 3초 간격, 3회 실패)만 있으면 뜨기 전에 재시작을 반복한다. 최대 3분(5초 x 36회) 기다린다.
     */
    private io.fabric8.kubernetes.api.model.Probe startupProbe(String path, int port) {
        return new ProbeBuilder()
                .withNewHttpGet()
                    .withPath(path)
                    .withPort(new IntOrString(port))
                    .withScheme("HTTP")
                .endHttpGet()
                .withPeriodSeconds(5)
                .withFailureThreshold(36)
                .build();
    }

    private io.fabric8.kubernetes.api.model.Probe httpProbe(String path, int port) {
        return new ProbeBuilder()
                .withNewHttpGet()
                    .withPath(path)
                    .withPort(new IntOrString(port))
                    .withScheme("HTTP")
                .endHttpGet()
                .withInitialDelaySeconds(5)
                .withPeriodSeconds(3)
                .build();
    }

    private void deleteTargetQuietly(String namespace, String name, List<String> logs) {
        try {
            k8sClient.apps().deployments().inNamespace(namespace).withName(name).delete();
            logs.add("step3: deleted failed deployment " + name);
        } catch (KubernetesClientException e) {
            log.error("cleanup failed. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("step3: cleanup failed " + name + " — " + e.getMessage());
        }
    }

    private static String deploymentName(String appName, String color) {
        return appName + "-" + color;
    }

    private static String serviceName(String appName) {
        return appName + "-svc";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
