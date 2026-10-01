package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 새 이미지를 {@code canary} 슬롯에 올리고, 입구 비율만 0 에서 100 까지 올린다.
 * 두 슬롯의 replica 는 {@code lily.deploy.replicas} 그대로다. 본 Service 는 {@code track=stable} 만 본다.
 *
 * <p>칸은 {@code lily.deploy.canary-weight-percent} (1 이상 50 이하)다.
 * 100 이 되면 새 이미지를 {@code stable} 로 옮기고 canary Ingress 와 슬롯을 지운다.
 * 중간이 실패하면 canary 를 지우고 사용자 트래픽은 이전 stable 에 남긴다.
 * 슬롯이 없으면 첫 배포로 보고 {@code stable} 에 전량을 올린다.
 */
public class CanaryDeploymentStrategy implements DeploymentStrategy {

    private static final Logger log = LoggerFactory.getLogger(CanaryDeploymentStrategy.class);
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
    public String finalSlot(SlotPlan plan) {
        return TRACK_CANARY.equals(plan.target()) ? TRACK_STABLE : plan.target();
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
        logs.add("canary: stable present -> target=canary step=" + properties.getCanaryWeightPercent());
        return new SlotPlan(TRACK_CANARY, TRACK_STABLE);
    }

    @Override
    public void applyTarget(
            DeployCommand command,
            String namespace,
            SlotPlan plan,
            Map<String, String> databaseEnv,
            List<String> logs) {
        int replicas = replicas();
        String name = deploymentName(command.appName(), plan.target());
        Map<String, String> plain = plainEnv(command, plan.target());
        Map<String, String> secretEnv = DatabaseSecret.entries(databaseEnv, plain.keySet());
        Deployment deployment = buildDeployment(command, namespace, plan.target(), replicas, plain, secretEnv);
        try {
            DatabaseSecret.apply(k8sClient, namespace, command.appName(), name, secretEnv);
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
        boolean replaced = false;
        try {
            ensureService(command, namespace, serviceName);
            if (TRACK_CANARY.equals(plan.target())) {
                raise(command, namespace, logs);
                replaced = replaceStable(command, namespace);
                finishPromote(command, namespace, logs);
            }
            logs.add("canary: service " + serviceName + " selector app=" + command.appName()
                    + ",track=" + TRACK_STABLE
                    + " port " + SERVICE_PORT + " -> " + command.targetPort());
        } catch (RuntimeException e) {
            log.error("failed to switch canary traffic. namespace={} name={} message={}",
                    namespace, serviceName, e.getMessage(), e);
            if (TRACK_CANARY.equals(plan.target()) && !replaced) {
                restoreStable(namespace, command.appName(), logs);
                logs.add("canary: traffic switch failed. stable 로 되돌림");
            } else {
                logs.add("canary: traffic switch failed");
            }
            if (e instanceof DeploymentFailedException failed) {
                throw failed;
            }
            throw new DeploymentFailedException("canary 트래픽 전환 실패: " + serviceName, logs, e);
        }
    }

    /**
     * 두 슬롯 replica 를 유지한 채 canary Ingress 가중치를 0 에서 100 까지 올린다.
     */
    private void raise(DeployCommand command, String namespace, List<String> logs) {
        String stableName = deploymentName(command.appName(), TRACK_STABLE);
        int count = replicas();
        scale(namespace, stableName, count);
        waitReady(namespace, stableName);
        ensureCanaryService(command, namespace);
        int step = properties.getCanaryWeightPercent();
        int weight = 0;
        while (true) {
            putWeight(command, namespace, weight);
            logs.add("canary: weight=" + weight + " stable=" + count + " canary=" + count);
            if (weight >= 100) {
                break;
            }
            hold(logs);
            weight = Math.min(100, weight + step);
        }
    }

    /**
     * 100%가 된 새 이미지를 stable 스펙에 쓴다.
     *
     * @return 스펙을 바꾼 뒤면 true. 그 전에 실패하면 이전 이미지로 되돌릴 수 있다
     */
    private boolean replaceStable(DeployCommand command, String namespace) {
        String canaryName = deploymentName(command.appName(), TRACK_CANARY);
        String stableName = deploymentName(command.appName(), TRACK_STABLE);
        Map<String, String> secretEnv = secretData(namespace, canaryName);
        Map<String, String> plain = plainEnv(command, TRACK_STABLE);
        DatabaseSecret.apply(k8sClient, namespace, command.appName(), stableName, secretEnv);
        Deployment stable = buildDeployment(command, namespace, TRACK_STABLE, replicas(), plain, secretEnv);
        copyAnnotations(namespace, canaryName, stable);
        k8sClient.apps().deployments().inNamespace(namespace).resource(stable).createOrReplace();
        return true;
    }

    /** stable 이 Ready 가 된 뒤에 canary 를 지운다. 다음 배포는 이 stable 을 기준으로 다시 비율을 올린다. */
    private void finishPromote(DeployCommand command, String namespace, List<String> logs) {
        String canaryName = deploymentName(command.appName(), TRACK_CANARY);
        String stableName = deploymentName(command.appName(), TRACK_STABLE);
        waitReady(namespace, stableName);
        deleteCanaryIngress(namespace, command.appName());
        copySchema(namespace, command.appName());
        k8sClient.apps().deployments().inNamespace(namespace).withName(canaryName).delete();
        k8sClient.services().inNamespace(namespace).withName(canaryServiceName(command.appName())).delete();
        logs.add("canary: promoted " + command.imageUrl() + " to " + stableName
                + " replicas=" + replicas());
    }

    /** 비율을 올리다 실패하면 canary 입구를 지워 이전 이미지가 트래픽을 다시 전부 받게 한다. */
    private void restoreStable(String namespace, String appName, List<String> logs) {
        String stableName = deploymentName(appName, TRACK_STABLE);
        String canaryName = deploymentName(appName, TRACK_CANARY);
        try {
            deleteCanaryIngress(namespace, appName);
            k8sClient.services().inNamespace(namespace).withName(canaryServiceName(appName)).delete();
            k8sClient.apps().deployments().inNamespace(namespace).withName(canaryName).delete();
            Deployment stable = k8sClient.apps().deployments().inNamespace(namespace).withName(stableName).get();
            if (stable != null) {
                scale(namespace, stableName, replicas());
            }
            logs.add("canary: restored " + stableName + " replicas=" + replicas());
        } catch (KubernetesClientException e) {
            log.error("failed to restore stable. namespace={} name={} message={}",
                    namespace, stableName, e.getMessage(), e);
            logs.add("canary: restore failed " + stableName + " — " + e.getMessage());
        }
    }

    @Override
    public void retirePrevious(String namespace, String appName, SlotPlan plan, List<String> logs) {
        if (plan.previous() == null || plan.previous().isBlank()) {
            logs.add("canary: no previous slot, skip retire");
            return;
        }
        if (TRACK_CANARY.equals(plan.target())) {
            logs.add("canary: stable serves the new image");
            return;
        }
        logs.add("canary: no previous slot, skip retire");
    }

    private void validateWeight(List<String> logs) {
        int weight = properties.getCanaryWeightPercent();
        if (weight < 1 || weight > 50) {
            logs.add("canary: rejected weight=" + weight);
            throw new DeploymentFailedException(
                    "canary weight 는 1 이상 50 이하여야 한다. 현재 값: " + weight, logs, null);
        }
    }

    private int replicas() {
        int replicas = properties.getReplicas();
        if (replicas < 1 || replicas > 5) {
            throw new IllegalArgumentException("replicas 는 1 이상 5 이하여야 한다: " + replicas);
        }
        return replicas;
    }

    private void hold(List<String> logs) {
        int seconds = Math.max(0, properties.getCanaryStepSeconds());
        if (seconds == 0) {
            return;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeploymentFailedException("canary 가중치 대기가 중단되었다", logs, e);
        }
    }

    private void ensureService(DeployCommand command, String namespace, String serviceName) {
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName)
                    .withNamespace(namespace)
                    .addToLabels("app", command.appName())
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", command.appName(), "track", TRACK_STABLE))
                    .addNewPort()
                        .withName("http")
                        .withPort(SERVICE_PORT)
                        .withTargetPort(new IntOrString(command.targetPort()))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
        k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
    }

    private void ensureCanaryService(DeployCommand command, String namespace) {
        String name = canaryServiceName(command.appName());
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels("app", command.appName())
                    .addToLabels("track", TRACK_CANARY)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", command.appName(), "track", TRACK_CANARY))
                    .addNewPort()
                        .withName("http")
                        .withPort(SERVICE_PORT)
                        .withTargetPort(new IntOrString(command.targetPort()))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
        k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
    }

    /** ingress-nginx 가 같은 호스트의 본 Ingress 와 짝을 이룬다. weight 0 은 새 슬롯으로 보내지 않는다. */
    private void putWeight(DeployCommand command, String namespace, int weight) {
        String name = canaryIngressName(command.appName());
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("kubernetes.io/ingress.class", "nginx");
        annotations.put("nginx.ingress.kubernetes.io/canary", "true");
        annotations.put("nginx.ingress.kubernetes.io/canary-weight", Integer.toString(weight));
        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withIngressClassName("nginx")
                    .addNewRule()
                        .withHost(host(command))
                        .withNewHttp()
                            .addNewPath()
                                .withPath("/")
                                .withPathType("Prefix")
                                .withNewBackend()
                                    .withNewService()
                                        .withName(canaryServiceName(command.appName()))
                                        .withNewPort().withNumber(SERVICE_PORT).endPort()
                                    .endService()
                                .endBackend()
                            .endPath()
                        .endHttp()
                    .endRule()
                .endSpec()
                .build();
        k8sClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).createOrReplace();
    }

    private void deleteCanaryIngress(String namespace, String appName) {
        k8sClient.network().v1().ingresses().inNamespace(namespace).withName(canaryIngressName(appName)).delete();
    }

    private String host(DeployCommand command) {
        if (command.host() != null && !command.host().isBlank()) {
            return command.host();
        }
        String domain = command.domain() == null || command.domain().isBlank()
                ? properties.getDomain() : command.domain();
        return command.appName() + "." + domain;
    }

    private void scale(String namespace, String name, int replicas) {
        k8sClient.apps().deployments().inNamespace(namespace).withName(name).scale(replicas);
    }

    private void waitReady(String namespace, String name) {
        long timeout = properties.getReadinessTimeoutSeconds();
        Deployment ready = k8sClient.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .waitUntilReady(timeout, TimeUnit.SECONDS);
        if (ready == null) {
            throw new KubernetesClientTimeoutException("Deployment", name, namespace, timeout, TimeUnit.SECONDS);
        }
    }

    /** mock 은 stringData 를 data 로 옮기지 않을 수 있다 */
    private Map<String, String> secretData(String namespace, String deploymentName) {
        Secret secret = k8sClient.secrets().inNamespace(namespace).withName(DatabaseSecret.name(deploymentName)).get();
        if (secret == null) {
            return Map.of();
        }
        if (secret.getStringData() != null && !secret.getStringData().isEmpty()) {
            return new LinkedHashMap<>(secret.getStringData());
        }
        if (secret.getData() == null || secret.getData().isEmpty()) {
            return Map.of();
        }
        Map<String, String> decoded = new LinkedHashMap<>();
        secret.getData().forEach((key, value) ->
                decoded.put(key, new String(Base64.getDecoder().decode(value))));
        return decoded;
    }

    private void copyAnnotations(String namespace, String from, Deployment to) {
        Deployment source = k8sClient.apps().deployments().inNamespace(namespace).withName(from).get();
        if (source == null || source.getMetadata().getAnnotations() == null) {
            return;
        }
        to.getMetadata().setAnnotations(new LinkedHashMap<>(source.getMetadata().getAnnotations()));
    }

    /** 롤백용 스키마 기록은 canary 슬롯에 있다. 승격한 stable 이 그 기록을 가져간다. */
    private void copySchema(String namespace, String appName) {
        String from = appName + "-" + TRACK_CANARY + "-schema";
        String to = appName + "-" + TRACK_STABLE + "-schema";
        var source = k8sClient.configMaps().inNamespace(namespace).withName(from).get();
        if (source == null) {
            return;
        }
        source.getMetadata().setName(to);
        source.getMetadata().setResourceVersion(null);
        source.getMetadata().setUid(null);
        source.getMetadata().setManagedFields(null);
        if (source.getMetadata().getLabels() != null) {
            source.getMetadata().getLabels().put("lily.io/slot", TRACK_STABLE);
        }
        k8sClient.configMaps().inNamespace(namespace).resource(source).createOrReplace();
        k8sClient.configMaps().inNamespace(namespace).withName(from).delete();
    }

    private Deployment buildDeployment(
            DeployCommand command,
            String namespace,
            String track,
            int replicas,
            Map<String, String> plain,
            Map<String, String> secretEnv) {
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
                                .withEnv(containerEnv(name, plain, secretEnv))
                                .withReadinessProbe(Probes.check(readinessPath, command.targetPort()))
                                .withLivenessProbe(Probes.check(livenessPath, command.targetPort()))
                                .withStartupProbe(Probes.startup(livenessPath, command.targetPort()))
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
        Deployment deployment = builder.build();
        SlotPods.preStop(deployment.getSpec().getTemplate().getSpec(), properties.getDrainSeconds());
        return deployment;
    }

    private static Map<String, String> plainEnv(DeployCommand command, String track) {
        Map<String, String> env = new LinkedHashMap<>();
        putAll(env, command.extraEnv());
        env.put("APP_VERSION", firstNonBlank(command.appVersion(), "dev"));
        env.put("SERVER_PORT", Integer.toString(command.targetPort()));
        env.put("APP_COLOR", track);
        return env;
    }

    /** DB 접속 정보는 Secret 참조로, 나머지는 값 그대로 넣는다 */
    private static List<EnvVar> containerEnv(
            String deploymentName, Map<String, String> plain, Map<String, String> secretEnv) {
        List<EnvVar> vars = DatabaseSecret.refs(deploymentName, secretEnv.keySet());
        plain.forEach((key, value) -> vars.add(new EnvVarBuilder().withName(key).withValue(value).build()));
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

    private static String canaryServiceName(String appName) {
        return appName + "-canary-svc";
    }

    private static String canaryIngressName(String appName) {
        return appName + "-canary-ingress";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
