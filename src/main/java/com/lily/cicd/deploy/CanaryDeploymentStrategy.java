package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.NginxIngressRouter;
import com.lily.cicd.module.TrafficRouter;
import com.lily.cicd.release.DeployConflictException;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 새 이미지를 쉬는 슬롯에 올리고, 입구 비율만 0 에서 100 까지 올린다.
 * 두 슬롯의 replica 는 올리는 동안 {@code lily.deploy.replicas} 그대로다.
 *
 * <p>칸은 {@code lily.deploy.canary-weight-percent} (1 이상 50 이하)다.
 * 100 이 되면 본 Service 의 {@code track} 을 새 슬롯으로 옮기고, 이전 슬롯은 replica 0 으로 남긴다.
 * 그 슬롯이 롤백이 되살리는 N-1 이다. 중간이 실패하면 가중치 입구와 새 슬롯을 지우고 트래픽은 이전 슬롯에 남긴다.
 * 슬롯이 없으면 첫 배포로 보고 {@code stable} 에 전량을 올린다.
 */
public class CanaryDeploymentStrategy implements DeploymentStrategy {

    private static final Logger log = LoggerFactory.getLogger(CanaryDeploymentStrategy.class);
    static final String TRACK_STABLE = "stable";
    static final String TRACK_CANARY = "canary";
    private static final int SERVICE_PORT = 80;

    private final KubernetesClient k8sClient;
    private final DeployProperties properties;
    /** 가중치 입구(canary Ingress)는 Router 모듈이 연다. Service·Deployment 는 이 전략이 계속 관리한다 */
    private final TrafficRouter router;

    /** Ingress 를 직접 쓰는 기본 Router 로 */
    public CanaryDeploymentStrategy(KubernetesClient k8sClient, DeployProperties properties) {
        this(k8sClient, properties, new NginxIngressRouter(k8sClient));
    }

    public CanaryDeploymentStrategy(KubernetesClient k8sClient, DeployProperties properties, TrafficRouter router) {
        this.k8sClient = k8sClient;
        this.properties = properties;
        this.router = router;
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
        String live = liveTrack(namespace, appName);
        if (TRACK_CANARY.equals(live)) {
            logs.add("canary: live=canary -> target=stable step=" + properties.getCanaryWeightPercent());
            return new SlotPlan(TRACK_STABLE, TRACK_CANARY);
        }
        logs.add("canary: live=stable -> target=canary step=" + properties.getCanaryWeightPercent());
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
            // 지우면 Pod 와 로그도 사라진다. 원인을 먼저 남긴다
            logs.addAll(PodDiagnostics.collect(k8sClient, namespace, targetName));
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
        boolean shifted = false;
        try {
            if (plan.previous() == null || plan.previous().isBlank()) {
                ensureService(command, namespace, serviceName, TRACK_STABLE);
                logs.add("canary: service " + serviceName + " selector app=" + command.appName()
                        + ",track=" + TRACK_STABLE
                        + " port " + SERVICE_PORT + " -> " + command.targetPort());
                return;
            }
            ensureService(command, namespace, serviceName, plan.previous());
            raise(command, namespace, plan, logs);
            pointService(namespace, serviceName, command.appName(), plan.target());
            shifted = true;
            deleteCanaryIngress(namespace, command.appName());
            k8sClient.services().inNamespace(namespace).withName(canaryServiceName(command.appName())).delete();
            logs.add("canary: shifted service " + serviceName + " track=" + plan.target()
                    + " image=" + command.imageUrl());
        } catch (RuntimeException e) {
            log.error("failed to switch canary traffic. namespace={} name={} message={}",
                    namespace, serviceName, e.getMessage(), e);
            if (!shifted && plan.previous() != null && !plan.previous().isBlank()) {
                restorePreviousTrack(namespace, command.appName(), plan, logs);
                logs.add("canary: traffic switch failed. " + plan.previous() + " 로 되돌림");
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
     * 가중치 Service 는 새 슬롯만 고른다. 본 Service 는 이전 슬롯을 그대로 본다.
     */
    private void raise(DeployCommand command, String namespace, SlotPlan plan, List<String> logs) {
        int count = replicas();
        scale(namespace, deploymentName(command.appName(), plan.previous()), count);
        scale(namespace, deploymentName(command.appName(), plan.target()), count);
        waitReady(namespace, deploymentName(command.appName(), plan.previous()));
        waitReady(namespace, deploymentName(command.appName(), plan.target()));
        ensureWeightService(command, namespace, plan.target());
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

    /** 비율을 올리다 실패하면 새 슬롯과 가중치 입구를 지워 이전 슬롯이 트래픽을 전부 받게 한다. */
    private void restorePreviousTrack(String namespace, String appName, SlotPlan plan, List<String> logs) {
        String previousName = deploymentName(appName, plan.previous());
        String targetName = deploymentName(appName, plan.target());
        try {
            router.closeCanary(namespace, appName);
            k8sClient.services().inNamespace(namespace).withName(canaryServiceName(appName)).delete();
            k8sClient.apps().deployments().inNamespace(namespace).withName(targetName).delete();
            Deployment previous = k8sClient.apps().deployments().inNamespace(namespace).withName(previousName).get();
            if (previous != null) {
                scale(namespace, previousName, replicas());
            }
            logs.add("canary: restored " + previousName + " replicas=" + replicas());
        } catch (RuntimeException e) {
            // k8s API 실패와 Router 모듈(HTTP) 실패 모두. 되돌리기 실패는 기록만 하고 원래 예외를 살린다
            log.error("failed to restore previous track. namespace={} name={} message={}",
                    namespace, previousName, e.getMessage(), e);
            logs.add("canary: restore failed " + previousName + " — " + e.getMessage());
        }
    }

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
            SlotPods.waitBeforeRemove(properties.getDrainSeconds(), logs);
            scale(namespace, name, 0);
            logs.add("canary: retired " + name + " replicas=0");
        } catch (KubernetesClientException e) {
            log.error("failed to retire previous. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("canary: retire failed. traffic 은 이미 새 track 을 보고 있다");
            throw new DeploymentFailedException(
                    "이전 deployment scale down 실패: " + name + ". 트래픽은 새 track 을 보고 있다",
                    logs, e);
        }
    }

    @Override
    public void discardTarget(String namespace, String appName, SlotPlan plan, List<String> logs) {
        String name = deploymentName(appName, plan.target());
        try {
            k8sClient.apps().deployments().inNamespace(namespace).withName(name).delete();
            logs.add("canary: deleted rejected deployment " + name + ", traffic stays on " + plan.previous());
        } catch (KubernetesClientException e) {
            log.error("discard failed. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("canary: delete failed " + name + " — " + e.getMessage());
        }
    }

    /**
     * 지금 selector 의 반대 트랙이 되살릴 대상이다. replica 0 으로 남겨 둔 Deployment 가 있어야 한다.
     */
    @Override
    public SlotPlan planRollback(String namespace, String appName, List<String> logs) {
        String live = liveTrack(namespace, appName);
        if (!TRACK_STABLE.equals(live) && !TRACK_CANARY.equals(live)) {
            throw new DeployConflictException(appName + " 의 canary Service 가 없다 (track=" + live + ")");
        }
        String previous = TRACK_STABLE.equals(live) ? TRACK_CANARY : TRACK_STABLE;
        if (k8sClient.apps().deployments().inNamespace(namespace).withName(deploymentName(appName, previous)).get() == null) {
            throw new DeployConflictException("되살릴 이전 슬롯이 없다: " + deploymentName(appName, previous));
        }
        logs.add("rollback: active=" + live + " -> restore=" + previous);
        return new SlotPlan(previous, live);
    }

    /**
     * 이전 슬롯을 설정한 replica 로 올리고 Ready 를 기다린 뒤 selector 의 track 만 바꾼다.
     * Ready 가 되지 않으면 다시 0 으로 내리고 트래픽은 건드리지 않는다.
     */
    @Override
    public void restorePrevious(String namespace, String appName, SlotPlan plan, List<String> logs) {
        String name = deploymentName(appName, plan.target());
        long timeout = properties.getReadinessTimeoutSeconds();
        var deployment = k8sClient.apps().deployments().inNamespace(namespace).withName(name);
        deployment.scale(replicas());
        logs.add("rollback: scaled " + name + " replicas=" + replicas());
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
        pointService(namespace, serviceName(appName), appName, plan.target());
        deleteCanaryIngress(namespace, appName);
        logs.add("rollback: service " + serviceName(appName) + " selector track=" + plan.target());
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

    /** 이미 트래픽을 받는 track 이 있으면 그 값을 유지한다. 없으면 {@code track} 으로 만든다. */
    private void ensureService(DeployCommand command, String namespace, String serviceName, String track) {
        String keep = track;
        Service existing = k8sClient.services().inNamespace(namespace).withName(serviceName).get();
        if (existing != null && existing.getSpec() != null && existing.getSpec().getSelector() != null) {
            String current = existing.getSpec().getSelector().get("track");
            if (current != null && !current.isBlank()) {
                keep = current;
            }
        }
        writeService(namespace, serviceName, command.appName(), keep, command.targetPort());
    }

    /** 가중치 입구가 새 슬롯만 고르도록 하는 Service. 본 Service 와 이름이 다르다. */
    private void ensureWeightService(DeployCommand command, String namespace, String track) {
        String name = canaryServiceName(command.appName());
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels("app", command.appName())
                    .addToLabels("track", track)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", command.appName(), "track", track))
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

    private void pointService(String namespace, String serviceName, String appName, String track) {
        Service existing = k8sClient.services().inNamespace(namespace).withName(serviceName).get();
        int targetPort = SERVICE_PORT;
        if (existing != null && existing.getSpec() != null && existing.getSpec().getPorts() != null
                && !existing.getSpec().getPorts().isEmpty()
                && existing.getSpec().getPorts().get(0).getTargetPort() != null
                && existing.getSpec().getPorts().get(0).getTargetPort().getIntVal() != null) {
            targetPort = existing.getSpec().getPorts().get(0).getTargetPort().getIntVal();
        }
        writeService(namespace, serviceName, appName, track, targetPort);
    }

    private void writeService(String namespace, String serviceName, String appName, String track, int targetPort) {
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName)
                    .withNamespace(namespace)
                    .addToLabels("app", appName)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", appName, "track", track))
                    .addNewPort()
                        .withName("http")
                        .withPort(SERVICE_PORT)
                        .withTargetPort(new IntOrString(targetPort))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
        k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
    }

    private String liveTrack(String namespace, String appName) {
        Service service = k8sClient.services().inNamespace(namespace).withName(serviceName(appName)).get();
        if (service == null || service.getSpec() == null || service.getSpec().getSelector() == null) {
            return null;
        }
        return service.getSpec().getSelector().get("track");
    }

    /** ingress-nginx 가 같은 호스트의 본 Ingress 와 짝을 이룬다. weight 0 은 새 슬롯으로 보내지 않는다. */
    private void putWeight(DeployCommand command, String namespace, int weight) {
        router.openCanary(routerContext(command, namespace), canaryServiceName(command.appName()), weight);
    }

    private void deleteCanaryIngress(String namespace, String appName) {
        router.closeCanary(namespace, appName);
    }

    /**
     * Router 모듈에 넘길 값. canary 입구는 이번 배포의 host 로 열고,
     * 라우트가 아직 없으면 Router 가 본 Service({@code {app}-svc}) 로 먼저 등록한다
     */
    private DeployContext routerContext(DeployCommand command, String namespace) {
        return new DeployContext(
                command.appName(),
                namespace,
                command.imageUrl(),
                command.targetPort(),
                SERVICE_PORT,
                host(command),
                firstNonBlank(command.appVersion(), "dev"),
                null,
                serviceName(command.appName()),
                DeploymentEngine.METRICS_PATH);
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

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
