package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 새 이미지를 {@code canary} 슬롯에만 올리고, 전체 파드 중 일부만 그 슬롯으로 둔다.
 * Service selector 는 {@code app} 만 보므로 트래픽은 Ready 파드 수 비율로 나뉜다.
 * {@code stable} 은 지우지 않는다.
 *
 * <p>슬롯이 없으면 첫 배포로 보고 {@code stable} 에 전량을 올린다.
 * 가중치는 {@code lily.deploy.canary-weight-percent} 이고 1 이상 50 이하다.
 * 파드 합은 5개다. 1% 처럼 1개 미만으로 떨어지는 비율도 canary 파드는 1개를 유지한다.
 */
public class CanaryDeploymentStrategy implements DeploymentStrategy {

    private static final Logger log = LoggerFactory.getLogger(CanaryDeploymentStrategy.class);
    static final int TOTAL_REPLICAS = 5;
    static final String TRACK_STABLE = "stable";
    static final String TRACK_CANARY = "canary";
    private static final int SERVICE_PORT = 80;

    private final KubernetesClient k8sClient;
    private final DeployProperties properties;

    public CanaryDeploymentStrategy(KubernetesClient k8sClient, DeployProperties properties) {
        this.k8sClient = k8sClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "canary";
    }

    @Override
    public SlotPlan plan(String namespace, String appName, List<String> logs) {
        validateWeight(logs);
        String stableName = deploymentName(appName, TRACK_STABLE);
        Deployment stable;
        try {
            stable = k8sClient.apps().deployments().inNamespace(namespace).withName(stableName).get();
        } catch (KubernetesClientException e) {
            log.error("failed to read stable deployment. namespace={} name={} code={} message={}",
                    namespace, stableName, e.getCode(), e.getMessage(), e);
            throw new DeploymentFailedException("stable deployment 조회 실패: " + stableName, logs, e);
        }

        if (stable == null) {
            logs.add("canary: stable absent -> target=stable weight=100");
            return new SlotPlan(TRACK_STABLE, "");
        }
        logs.add("canary: stable present -> target=canary weight=" + properties.getCanaryWeightPercent());
        return new SlotPlan(TRACK_CANARY, TRACK_STABLE);
    }

    @Override
    public void applyTarget(
            DeployCommand command,
            String namespace,
            SlotPlan plan,
            Map<String, String> databaseEnv,
            List<String> logs) {
        int replicas = TRACK_STABLE.equals(plan.target()) ? TOTAL_REPLICAS : canaryReplicas();
        String name = deploymentName(command.appName(), plan.target());
        Deployment deployment = buildDeployment(command, namespace, plan.target(), replicas, databaseEnv);
        try {
            k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
            logs.add("canary: applied deployment " + name + " replicas=" + replicas
                    + " image=" + command.imageUrl());
        } catch (KubernetesClientException e) {
            log.error("failed to apply deployment. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("canary: apply failed " + name + " — service 는 변경하지 않음");
            throw new DeploymentFailedException("target deployment 적용 실패: " + name, logs, e);
        }
    }

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
            logs.add("canary: deployment ready " + targetName);
        } catch (KubernetesClientException e) {
            log.error("readiness wait failed. namespace={} name={} timeoutSeconds={} code={} message={}",
                    namespace, targetName, timeout, e.getCode(), e.getMessage(), e);
            deleteTargetQuietly(namespace, targetName, logs);
            logs.add("canary: aborted. active service was not modified");
            throw new DeploymentFailedException(
                    "target deployment 가 " + timeout + "초 안에 Ready 가 되지 않음: " + targetName,
                    logs, e);
        }
    }

    @Override
    public void switchTraffic(DeployCommand command, String namespace, SlotPlan plan, List<String> logs) {
        String serviceName = serviceName(command.appName());
        try {
            if (TRACK_CANARY.equals(plan.target())) {
                String stableName = deploymentName(command.appName(), TRACK_STABLE);
                k8sClient.apps().deployments()
                        .inNamespace(namespace)
                        .withName(stableName)
                        .scale(stableReplicas());
                logs.add("canary: scaled stable " + stableName + " replicas=" + stableReplicas());
            }
            Service service = new ServiceBuilder()
                    .withNewMetadata()
                        .withName(serviceName)
                        .withNamespace(namespace)
                        .addToLabels("app", command.appName())
                    .endMetadata()
                    .withNewSpec()
                        .withType("ClusterIP")
                        .withSelector(Map.of("app", command.appName()))
                        .addNewPort()
                            .withName("http")
                            .withPort(SERVICE_PORT)
                            .withTargetPort(new IntOrString(command.targetPort()))
                            .withProtocol("TCP")
                        .endPort()
                    .endSpec()
                    .build();
            k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
            logs.add("canary: service " + serviceName + " selector app=" + command.appName()
                    + " port " + SERVICE_PORT + " -> " + command.targetPort());
        } catch (KubernetesClientException e) {
            log.error("failed to switch canary traffic. namespace={} name={} code={} message={}",
                    namespace, serviceName, e.getCode(), e.getMessage(), e);
            logs.add("canary: traffic switch failed. 새 Deployment 는 남겨 둠");
            throw new DeploymentFailedException("canary 트래픽 전환 실패: " + serviceName, logs, e);
        }
    }

    /**
     * stable 은 계속 트래픽을 받는다. replica 를 0 으로 만들지 않는다.
     */
    @Override
    public void retirePrevious(String namespace, String appName, SlotPlan plan, List<String> logs) {
        if (plan.previous() == null || plan.previous().isBlank()) {
            logs.add("canary: no previous slot, skip retire");
            return;
        }
        String name = deploymentName(appName, plan.previous());
        try {
            Deployment existing = k8sClient.apps().deployments().inNamespace(namespace).withName(name).get();
            if (existing == null) {
                logs.add("canary: previous deployment absent, skip retire " + name);
                return;
            }
            int replicas = existing.getSpec().getReplicas() == null ? 0 : existing.getSpec().getReplicas();
            logs.add("canary: keep " + name + " replicas=" + replicas);
        } catch (KubernetesClientException e) {
            log.error("failed to read previous deployment. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("canary: retire check failed. stable 은 스케일 다운하지 않음");
            throw new DeploymentFailedException("이전 deployment 확인 실패: " + name, logs, e);
        }
    }

    private void validateWeight(List<String> logs) {
        int weight = properties.getCanaryWeightPercent();
        if (weight < 1 || weight > 50) {
            logs.add("canary: rejected weight=" + weight);
            throw new DeploymentFailedException(
                    "canary weight 는 1 이상 50 이하여야 한다. 현재 값: " + weight, logs, null);
        }
    }

    private int canaryReplicas() {
        return Math.max(1, TOTAL_REPLICAS * properties.getCanaryWeightPercent() / 100);
    }

    private int stableReplicas() {
        return TOTAL_REPLICAS - canaryReplicas();
    }

    private Deployment buildDeployment(
            DeployCommand command,
            String namespace,
            String track,
            int replicas,
            Map<String, String> databaseEnv) {
        String appName = command.appName();
        String name = deploymentName(appName, track);
        Map<String, String> labels = Map.of("app", appName, "track", track);
        String readinessPath = firstNonBlank(command.readinessPath(), properties.getReadinessPath());
        String livenessPath = firstNonBlank(command.livenessPath(), properties.getLivenessPath());

        DeploymentBuilder builder = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
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
                                .withEnv(containerEnv(command, track, databaseEnv))
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

    private List<EnvVar> containerEnv(DeployCommand command, String track, Map<String, String> databaseEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        putAll(env, databaseEnv);
        putAll(env, command.extraEnv());
        env.put("APP_VERSION", firstNonBlank(command.appVersion(), "dev"));
        env.put("SERVER_PORT", Integer.toString(command.targetPort()));
        env.put("APP_COLOR", track);

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
            logs.add("canary: deleted failed deployment " + name);
        } catch (KubernetesClientException e) {
            log.error("cleanup failed. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("canary: cleanup failed " + name + " — " + e.getMessage());
        }
    }

    private static String deploymentName(String appName, String track) {
        return appName + "-" + track;
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
