package com.lily.cicd.deploy;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ready 가 되지 못한 슬롯을 지우기 전에 왜 그런지 남긴다.
 * 컨테이너 상태(재시작, 종료 코드), Warning 이벤트, 마지막 로그를 "diagnosis:" 줄로 붙인다.
 * 사용자 화면까지 가는 값이라 컨테이너에 넣은 환경변수 값과 Secret 값은 가린다.
 */
final class PodDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(PodDiagnostics.class);

    static final String PREFIX = "diagnosis: ";
    private static final int LOG_LINES = 40;
    private static final int MAX_LINE = 400;
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|credential)([\"']?\\s*[=:]\\s*[\"']?)[^\\s\"',;&]+");
    private static final Pattern URL_USERINFO = Pattern.compile("(://[^/\\s:@]+:)[^@\\s/]+@");

    private PodDiagnostics() {
    }

    /** 실패해도 배포 실패 처리를 막지 않는다. 모을 수 없으면 빈 목록 */
    static List<String> collect(KubernetesClient client, String namespace, String deploymentName) {
        List<String> lines = new ArrayList<>();
        try {
            Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
            if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getSelector() == null) {
                return lines;
            }
            Set<String> hidden = hiddenValues(client, namespace, deployment);
            Map<String, String> labels = deployment.getSpec().getSelector().getMatchLabels();
            List<Pod> pods = client.pods().inNamespace(namespace).withLabels(labels).list().getItems();
            if (pods.isEmpty()) {
                lines.add(PREFIX + "pod 가 만들어지지 않았다");
                return lines;
            }
            Pod pod = pods.stream()
                    .max(Comparator.comparingInt(PodDiagnostics::restarts))
                    .orElse(pods.get(0));
            String podName = pod.getMetadata().getName();
            containerState(pod, lines);
            events(client, namespace, podName, lines);
            logTail(client, namespace, pod, lines);
            lines.replaceAll(line -> redact(line, hidden));
        } catch (KubernetesClientException | IllegalArgumentException e) {
            log.warn("pod diagnosis failed. namespace={} deployment={} message={}",
                    namespace, deploymentName, e.getMessage());
        }
        return lines;
    }

    private static void containerState(Pod pod, List<String> lines) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            String phase = pod.getStatus() == null ? "unknown" : pod.getStatus().getPhase();
            lines.add(PREFIX + "pod phase=" + phase);
            return;
        }
        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            StringBuilder line = new StringBuilder(PREFIX + "container " + status.getName()
                    + " restarts=" + status.getRestartCount());
            ContainerStateWaiting waiting = status.getState() == null ? null : status.getState().getWaiting();
            if (waiting != null) {
                line.append(" waiting=").append(waiting.getReason());
            }
            ContainerStateTerminated last = status.getLastState() == null ? null : status.getLastState().getTerminated();
            if (last != null) {
                line.append(" lastExit=").append(last.getExitCode()).append(' ').append(last.getReason());
            }
            lines.add(line.toString());
        }
    }

    private static void events(KubernetesClient client, String namespace, String podName, List<String> lines) {
        try {
            List<Event> events = client.v1().events().inNamespace(namespace)
                    .withField("involvedObject.name", podName).list().getItems();
            Set<String> seen = new LinkedHashSet<>();
            for (Event event : events) {
                if ("Warning".equals(event.getType())) {
                    seen.add(PREFIX + "event " + event.getReason() + ": " + trim(event.getMessage()));
                }
            }
            lines.addAll(seen);
        } catch (KubernetesClientException e) {
            log.debug("events unavailable. pod={} message={}", podName, e.getMessage());
        }
    }

    /** 재시작했으면 죽기 직전 컨테이너의 로그가 원인이다 */
    private static void logTail(KubernetesClient client, String namespace, Pod pod, List<String> lines) {
        String podName = pod.getMetadata().getName();
        String text = null;
        try {
            var resource = client.pods().inNamespace(namespace).withName(podName);
            if (restarts(pod) > 0) {
                text = resource.terminated().tailingLines(LOG_LINES).getLog();
            }
            if (text == null || text.isBlank()) {
                text = resource.tailingLines(LOG_LINES).getLog();
            }
        } catch (KubernetesClientException e) {
            log.debug("log unavailable. pod={} message={}", podName, e.getMessage());
        }
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(PREFIX + "log " + trim(line));
            }
        }
    }

    /** 컨테이너에 직접 넣은 env 값과 envFrom / secretKeyRef 로 붙인 Secret 값 */
    private static Set<String> hiddenValues(KubernetesClient client, String namespace, Deployment deployment) {
        Set<String> values = new LinkedHashSet<>();
        Set<String> secrets = new LinkedHashSet<>();
        var spec = deployment.getSpec().getTemplate().getSpec();
        if (spec == null || spec.getContainers() == null) {
            return values;
        }
        spec.getContainers().forEach(container -> {
            if (container.getEnv() != null) {
                for (EnvVar env : container.getEnv()) {
                    if (env.getValue() != null) {
                        values.add(env.getValue());
                    }
                    if (env.getValueFrom() != null && env.getValueFrom().getSecretKeyRef() != null) {
                        secrets.add(env.getValueFrom().getSecretKeyRef().getName());
                    }
                }
            }
            if (container.getEnvFrom() != null) {
                for (EnvFromSource from : container.getEnvFrom()) {
                    if (from.getSecretRef() != null) {
                        secrets.add(from.getSecretRef().getName());
                    }
                }
            }
        });
        for (String name : secrets) {
            try {
                Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
                if (secret != null && secret.getData() != null) {
                    secret.getData().values().forEach(encoded -> values.add(
                            new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)));
                }
                if (secret != null && secret.getStringData() != null) {
                    values.addAll(secret.getStringData().values());
                }
            } catch (KubernetesClientException | IllegalArgumentException e) {
                log.debug("secret unavailable. name={} message={}", name, e.getMessage());
            }
        }
        // 짧은 값(포트, true 같은)까지 가리면 로그를 읽을 수 없다
        values.removeIf(value -> value == null || value.length() < 6);
        return values;
    }

    static String redact(String line, Set<String> hidden) {
        String result = line;
        for (String value : hidden) {
            result = result.replace(value, "***");
        }
        result = URL_USERINFO.matcher(result).replaceAll("$1***@");
        return CREDENTIAL.matcher(result).replaceAll("$1$2***");
    }

    private static int restarts(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return 0;
        }
        return pod.getStatus().getContainerStatuses().stream().mapToInt(ContainerStatus::getRestartCount).sum();
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String line = value.strip();
        return line.length() > MAX_LINE ? line.substring(0, MAX_LINE) + "…" : line;
    }
}
