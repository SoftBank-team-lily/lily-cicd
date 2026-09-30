package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.DeployStages;
import com.lily.cicd.module.TrafficRouter;
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
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * k3s 위에서 앱 하나를 블루그린으로 배포한다.
 *
 * <p>앱 이름 {@code lily} 기준으로 CICD 가 직접 만드는 오브젝트:
 * <ul>
 *   <li>Deployment {@code lily-blue}, {@code lily-green}</li>
 *   <li>Service {@code lily-svc} — selector 의 {@code color} 가 트래픽 스위치</li>
 * </ul>
 * Ingress 는 Router 모듈 자리다. 구현이 없으면 {@code NginxIngressRouter} 가 {@code lily-ingress} 를 만든다.
 * DB, Logging, Monitoring 도 같은 방식으로 빈만 바꾸면 붙는다.
 *
 * <p>kubectl 은 호출하지 않는다. Fabric8 Kubernetes Client 만 사용한다.
 * 인스턴스 필드는 클라이언트와 설정뿐이라 동시에 여러 앱을 배포해도 색 상태가 섞이지 않는다.
 * 한 번의 실행 기록은 메서드 지역 변수 {@code logs} 에만 쌓인다.
 *
 * <p>lily-blog-sample 계약: 컨테이너 포트 8080, readiness {@code /actuator/health/readiness},
 * 환경변수 {@code APP_COLOR} / {@code APP_VERSION}. 샘플 앱은 {@code /} 와 {@code /health} 를 제공하지 않는다.
 */
@org.springframework.stereotype.Service // Kubernetes Service 와 이름이 겹쳐 전체 이름을 쓴다.
public class K8sBlueGreenDeployer {

    private static final Logger log = LoggerFactory.getLogger(K8sBlueGreenDeployer.class);
    private static final int REPLICAS = 1;
    private static final int SERVICE_PORT = 80;
    private static final String COLOR_BLUE = "blue";
    private static final String COLOR_GREEN = "green";

    private static final String METRICS_PATH = "/actuator/prometheus";

    private final KubernetesClient k8sClient;
    private final DeployProperties properties;
    private final DatabaseProvisioner databaseProvisioner;
    private final TrafficRouter trafficRouter;
    private final DeployLog deployLog;
    private final DeployMonitor deployMonitor;

    public K8sBlueGreenDeployer(
            KubernetesClient k8sClient,
            DeployProperties properties,
            DatabaseProvisioner databaseProvisioner,
            TrafficRouter trafficRouter,
            DeployLog deployLog,
            DeployMonitor deployMonitor) {
        this.k8sClient = k8sClient;
        this.properties = properties;
        this.databaseProvisioner = databaseProvisioner;
        this.trafficRouter = trafficRouter;
        this.deployLog = deployLog;
        this.deployMonitor = deployMonitor;
    }

    @Async
    public CompletableFuture<DeploymentResultDto> deploy(String appName, String imageUrl, int targetPort) {
        return deploy(BlueGreenDeployCommand.of(appName, imageUrl, targetPort));
    }

    @Async
    public CompletableFuture<DeploymentResultDto> deploy(BlueGreenDeployCommand command) {
        try {
            return CompletableFuture.completedFuture(execute(command));
        } catch (DeploymentFailedException e) {
            log.error("blue-green aborted. message={}", e.getMessage(), e);
            throw e;
        } catch (KubernetesClientException e) {
            log.error("kubernetes api failed. code={} message={}", e.getCode(), e.getMessage(), e);
            throw new DeploymentFailedException(
                    "kubernetes api 호출 실패: " + e.getMessage(), List.of(), e);
        }
    }

    private DeploymentResultDto execute(BlueGreenDeployCommand command) {
        command = applyDefaults(command);
        validate(command);
        List<String> logs = new ArrayList<>();
        String namespace = firstNonBlank(command.namespace(), properties.getNamespace());
        String appName = command.appName();
        String host = appName + "." + firstNonBlank(command.domain(), properties.getDomain());
        DeployContext context = new DeployContext(
                appName,
                namespace,
                command.imageUrl(),
                command.targetPort(),
                SERVICE_PORT,
                host,
                firstNonBlank(command.appVersion(), "dev"),
                null,
                serviceName(appName),
                METRICS_PATH);

        try {
            record(context, logs, DeployStages.STARTED, "deploy started image=" + command.imageUrl());
            Map<String, String> databaseEnv = prepareDatabase(context, logs);

            ColorChoice colors = step1ResolveColors(namespace, appName, logs);
            context = context.withTargetColor(colors.target());
            record(context, logs, DeployStages.COLOR, last(logs));

            String targetName = deploymentName(appName, colors.target());
            step2ApplyTargetDeployment(command, namespace, colors.target(), databaseEnv, logs);
            record(context, logs, DeployStages.DEPLOYMENT, last(logs));

            step3WaitUntilReady(namespace, targetName, logs);
            record(context, logs, DeployStages.READY, last(logs));

            step4SwitchService(command, namespace, colors.target(), logs);
            record(context, logs, DeployStages.SERVICE, last(logs));

            route(context, logs);
            watch(context, logs);
            step6ScaleDownOld(namespace, appName, colors.current(), logs);
            record(context, logs, DeployStages.SCALE_DOWN, last(logs));

            record(context, logs, DeployStages.SUCCEEDED,
                    "cutover complete. active=" + colors.target() + " host=" + host);
            return new DeploymentResultDto("SUCCESS", colors.target(), "http://" + host, List.copyOf(logs));
        } catch (RuntimeException e) {
            notifyFailure(context, logs, e);
            throw e;
        }
    }

    private Map<String, String> prepareDatabase(DeployContext context, List<String> logs) {
        try {
            Map<String, String> env = databaseProvisioner.prepare(context);
            if (env == null) {
                env = Map.of();
            }
            record(context, logs, DeployStages.DATABASE, "database: env keys=" + env.keySet());
            return env;
        } catch (RuntimeException e) {
            logs.add("database: prepare failed — deployment 를 만들지 않음");
            log.error("database module failed. app={} message={}", context.appName(), e.getMessage(), e);
            throw new DeploymentFailedException("DB 모듈 준비 실패", logs, e);
        }
    }

    private void route(DeployContext context, List<String> logs) {
        try {
            trafficRouter.route(context);
            record(context, logs, DeployStages.ROUTER,
                    "router: " + context.host() + " -> " + context.serviceName() + ":" + context.servicePort());
        } catch (RuntimeException e) {
            log.error("router module failed. app={} host={} message={}",
                    context.appName(), context.host(), e.getMessage(), e);
            logs.add("router: 적용 실패. service selector 는 이미 target 을 가리킬 수 있음");
            throw new DeploymentFailedException("router 적용 실패: " + context.host(), logs, e);
        }
    }

    private void watch(DeployContext context, List<String> logs) {
        try {
            deployMonitor.attached(context);
            record(context, logs, DeployStages.MONITOR,
                    "monitor: " + context.serviceName() + ":" + context.targetPort() + context.metricsPath());
        } catch (RuntimeException e) {
            log.warn("monitoring module failed. deploy continues. app={} message={}",
                    context.appName(), e.getMessage(), e);
            record(context, logs, DeployStages.MONITOR, "monitor: attach failed, deploy continues");
        }
    }

    private void notifyFailure(DeployContext context, List<String> logs, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        try {
            deployLog.record(context, DeployStages.FAILED, message);
        } catch (RuntimeException logError) {
            log.warn("logging module failed while reporting failure. message={}", logError.getMessage(), logError);
        }
        try {
            deployMonitor.failed(context, message);
        } catch (RuntimeException monitorError) {
            log.warn("monitoring module failed while reporting failure. message={}",
                    monitorError.getMessage(), monitorError);
        }
        logs.add("failed: " + message);
    }

    /**
     * Service selector 의 color 가 지금 트래픽을 받는 색이다.
     * Service 가 없거나 color 가 green 이면 다음은 blue. color 가 blue 이면 다음은 green.
     */
    private ColorChoice step1ResolveColors(String namespace, String appName, List<String> logs) {
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
            return new ColorChoice(COLOR_BLUE, COLOR_GREEN);
        }
        if (COLOR_BLUE.equals(color)) {
            logs.add("step1: service=" + serviceName + " color=blue -> target=green current=blue");
            return new ColorChoice(COLOR_GREEN, COLOR_BLUE);
        }

        logs.add("step1: unsupported color=" + color);
        throw new DeploymentFailedException(
                "selector color 는 blue 또는 green 만 지원한다. 현재 값: " + color, logs, null);
    }

    private void step2ApplyTargetDeployment(
            BlueGreenDeployCommand command,
            String namespace,
            String targetColor,
            Map<String, String> databaseEnv,
            List<String> logs) {
        String name = deploymentName(command.appName(), targetColor);
        Deployment deployment = buildDeployment(command, namespace, targetColor, databaseEnv);
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
    private void step3WaitUntilReady(String namespace, String targetName, List<String> logs) {
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

    private void step4SwitchService(
            BlueGreenDeployCommand command, String namespace, String targetColor, List<String> logs) {
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
                    .withSelector(Map.of("app", command.appName(), "color", targetColor))
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
            logs.add("step4: service " + name + " selector color=" + targetColor
                    + " port " + SERVICE_PORT + " -> " + targetPort);
        } catch (KubernetesClientException e) {
            log.error("failed to switch service. namespace={} name={} code={} message={}",
                    namespace, name, e.getCode(), e.getMessage(), e);
            logs.add("step4: service switch failed. 새 Deployment 는 남겨 둠. selector 는 바뀌지 않았을 수 있음");
            throw new DeploymentFailedException("service selector 전환 실패: " + name, logs, e);
        }
    }

    private void step6ScaleDownOld(String namespace, String appName, String currentColor, List<String> logs) {
        String name = deploymentName(appName, currentColor);
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

    private Deployment buildDeployment(
            BlueGreenDeployCommand command,
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
            BlueGreenDeployCommand command, String targetColor, Map<String, String> databaseEnv) {
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

    private void record(DeployContext context, List<String> logs, String stage, String detail) {
        if (logs.isEmpty() || !detail.equals(logs.get(logs.size() - 1))) {
            logs.add(detail);
        }
        try {
            deployLog.record(context, stage, detail);
        } catch (RuntimeException e) {
            log.warn("logging module failed. stage={} message={}", stage, e.getMessage(), e);
        }
    }

    private static String last(List<String> logs) {
        return logs.get(logs.size() - 1);
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

    private BlueGreenDeployCommand applyDefaults(BlueGreenDeployCommand command) {
        if (command == null) {
            return null;
        }
        String appName = firstNonBlank(command.appName(), properties.getAppName());
        if (appName.equals(command.appName())) {
            return command;
        }
        return new BlueGreenDeployCommand(
                appName,
                command.imageUrl(),
                command.targetPort(),
                command.namespace(),
                command.domain(),
                command.readinessPath(),
                command.livenessPath(),
                command.appVersion(),
                command.imagePullSecret(),
                command.extraEnv() == null ? Map.of() : command.extraEnv());
    }

    private void validate(BlueGreenDeployCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command 가 비어 있다");
        }
        if (command.appName() == null || !command.appName().matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException(
                    "appName 은 소문자, 숫자, 하이픈만 사용할 수 있다: " + command.appName());
        }
        if (command.appName().length() > 55) {
            throw new IllegalArgumentException("appName 은 55자 이하여야 한다. '-ingress' 접미사를 붙이면 63자를 넘긴다");
        }
        if (command.imageUrl() == null || command.imageUrl().isBlank()) {
            throw new IllegalArgumentException("imageUrl 이 비어 있다");
        }
        if (command.targetPort() < 1 || command.targetPort() > 65535) {
            throw new IllegalArgumentException("targetPort 범위가 아니다: " + command.targetPort());
        }
        if (properties.getReadinessTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("readinessTimeoutSeconds 는 1 이상이어야 한다");
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

    private record ColorChoice(String target, String current) {
    }
}
