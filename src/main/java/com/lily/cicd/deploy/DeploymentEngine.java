package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DatabaseProvisioner;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.DeployStages;
import com.lily.cicd.module.TrafficRouter;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 배포 요청을 받아 DB 를 준비한 뒤 {@link DeploymentStrategy} 에 클러스터 반영을 맡긴다.
 *
 * <p>기본 전략은 블루그린이다. Canary 등 다른 규칙은 {@code DeploymentStrategy} 빈을 등록하면 그 구현이 쓰인다.
 * Router, Logging, Monitoring 은 전략과 별도로 빈만 바꾸면 붙는다.
 *
 * <p>인스턴스 필드는 설정과 전략뿐이라 동시에 여러 앱을 배포해도 상태가 섞이지 않는다.
 * 한 번의 실행 기록은 메서드 지역 변수 {@code logs} 에만 쌓인다.
 */
@org.springframework.stereotype.Service
public class DeploymentEngine {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEngine.class);
    private static final int SERVICE_PORT = 80;
    private static final String METRICS_PATH = "/actuator/prometheus";

    private final DeployProperties properties;
    private final DeploymentStrategy strategy;
    private final DatabaseProvisioner databaseProvisioner;
    private final TrafficRouter trafficRouter;
    private final DeployLog deployLog;
    private final DeployMonitor deployMonitor;

    public DeploymentEngine(
            DeployProperties properties,
            DeploymentStrategy strategy,
            DatabaseProvisioner databaseProvisioner,
            TrafficRouter trafficRouter,
            DeployLog deployLog,
            DeployMonitor deployMonitor) {
        this.properties = properties;
        this.strategy = strategy;
        this.databaseProvisioner = databaseProvisioner;
        this.trafficRouter = trafficRouter;
        this.deployLog = deployLog;
        this.deployMonitor = deployMonitor;
    }

    @Async
    public CompletableFuture<DeploymentResultDto> deploy(String appName, String imageUrl, int targetPort) {
        return deploy(DeployCommand.of(appName, imageUrl, targetPort));
    }

    @Async
    public CompletableFuture<DeploymentResultDto> deploy(DeployCommand command) {
        try {
            return CompletableFuture.completedFuture(execute(command));
        } catch (DeploymentFailedException e) {
            log.error("deploy aborted. strategy={} message={}", strategy.name(), e.getMessage(), e);
            throw e;
        } catch (KubernetesClientException e) {
            log.error("kubernetes api failed. code={} message={}", e.getCode(), e.getMessage(), e);
            throw new DeploymentFailedException(
                    "kubernetes api 호출 실패: " + e.getMessage(), List.of(), e);
        }
    }

    private DeploymentResultDto execute(DeployCommand command) {
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
            record(context, logs, DeployStages.STARTED,
                    "deploy started strategy=" + strategy.name() + " image=" + command.imageUrl());
            Map<String, String> databaseEnv = prepareDatabase(context, logs);

            SlotPlan plan = strategy.plan(namespace, appName, logs);
            context = strategy.bind(context, plan);
            record(context, logs, DeployStages.COLOR, last(logs));

            strategy.applyTarget(command, namespace, plan, databaseEnv, logs);
            record(context, logs, DeployStages.DEPLOYMENT, last(logs));

            strategy.awaitReady(namespace, appName, plan, logs);
            record(context, logs, DeployStages.READY, last(logs));

            strategy.switchTraffic(command, namespace, plan, logs);
            record(context, logs, DeployStages.SERVICE, last(logs));

            route(context, logs);
            watch(context, logs);
            strategy.retirePrevious(namespace, appName, plan, logs);
            record(context, logs, DeployStages.SCALE_DOWN, last(logs));

            record(context, logs, DeployStages.SUCCEEDED,
                    "cutover complete. active=" + plan.target() + " host=" + host);
            return new DeploymentResultDto("SUCCESS", plan.target(), "http://" + host, List.copyOf(logs));
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

    private DeployCommand applyDefaults(DeployCommand command) {
        if (command == null) {
            return null;
        }
        String appName = firstNonBlank(command.appName(), properties.getAppName());
        if (appName.equals(command.appName())) {
            return command;
        }
        return new DeployCommand(
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

    private void validate(DeployCommand command) {
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
