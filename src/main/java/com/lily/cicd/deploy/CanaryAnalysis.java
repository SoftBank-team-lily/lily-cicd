package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployStages;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 블루그린 전환 전에 새 버전을 판정한다.
 *
 * <ol>
 *   <li>판정 시간 동안 새 버전과 이전 버전에 같은 요청을 클러스터 안에서 보내 에러율과 p95 를 잰다.
 *       사용자 트래픽은 옮기지 않는다. 판정이 끝나기 전에 공개 주소가 새 버전을 보면 안 된다</li>
 *   <li>새 버전 Pod 가 재시작하거나 Ready 가 빠지면 실패</li>
 * </ol>
 *
 * 판정 요청을 cicd 가 직접 보내는 이유: ingress-nginx 요청 메트릭이 수집되지 않는 환경이 있고,
 * 사용자 트래픽이 적으면 표본이 모자라다. 두 버전에 같은 요청을 보내면 비교 기준이 같다.
 * 판정이 끝나면 canary Service 를 {@link #cleanup} 으로 지운다.
 * 예전에 만들어 둔 canary Ingress 가 남아 있으면 같이 지운다.
 */
public class CanaryAnalysis {

    private static final Logger log = LoggerFactory.getLogger(CanaryAnalysis.class);
    private static final int SERVICE_PORT = 80;
    /** 표본이 이만큼 모였을 때 에러율이 이 값 이상이면 판정 시간을 채우지 않고 멈춘다 */
    private static final int EARLY_ABORT_SAMPLES = 10;
    private static final double EARLY_ABORT_ERROR_RATE = 0.5;
    private static final long WARM_UP_MILLIS = 15_000;

    private final KubernetesClient k8sClient;
    private final DeployProperties.CanaryAnalysis settings;
    private final String clusterDomain;
    private final HttpClient http;

    public CanaryAnalysis(KubernetesClient k8sClient, DeployProperties properties) {
        this(k8sClient, properties, "svc.cluster.local");
    }

    /** @param clusterDomain Service 주소 접미사. 테스트에서 바꾼다 */
    CanaryAnalysis(KubernetesClient k8sClient, DeployProperties properties, String clusterDomain) {
        this.k8sClient = k8sClient;
        this.settings = properties.getCanaryAnalysis();
        this.clusterDomain = clusterDomain;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(settings.getRequestTimeoutMillis()))
                .build();
    }

    /**
     * @param stage 단계 기록 (단계 이름, 한 줄)
     * @return 판정 결과. 판정할 수 없는 상황(첫 배포, 이전 버전이 떠 있지 않음)이면 {@link Verdict#skipped}
     */
    public Verdict judge(DeployContext context, String path, SlotPlan plan, BiConsumer<String, String> stage) {
        String namespace = context.namespace();
        String app = context.appName();
        String skip = skipReason(namespace, app, context.host(), plan);
        if (skip != null) {
            stage.accept(DeployStages.CANARY_ANALYSIS, "canary: skipped — " + skip);
            return Verdict.skipped(skip);
        }

        String canaryUrl = url(canaryServiceName(app), namespace, path);
        String stableUrl = url(DeploymentEngine.serviceName(app), namespace, path);

        // 방금 만든 canary Service 는 엔드포인트가 붙기 전까지 연결이 거절된다.
        // 응답을 한 번 받은 뒤에 사용자 트래픽을 열고, 그 요청은 표본에 넣지 않는다
        createService(context, plan);
        warmUp(canaryUrl);
        stage.accept(DeployStages.CANARY_TRAFFIC, "canary: probe only, user traffic stays on "
                + plan.previous() + " (" + context.host() + ")");
        stage.accept(DeployStages.CANARY_ANALYSIS, "canary: analysing " + path + " for "
                + settings.getDurationSeconds() + "s, new=" + plan.target() + " old=" + plan.previous());

        int restartsBefore = restarts(namespace, app, plan.target());
        List<Sample> canary = new CopyOnWriteArrayList<>();
        List<Sample> stable = new CopyOnWriteArrayList<>();
        long deadline = System.nanoTime() + settings.getDurationSeconds() * 1_000_000_000L;
        List<CompletableFuture<Void>> inflight = new ArrayList<>();
        String early = null;
        while (System.nanoTime() < deadline) {
            inflight.add(probe(canaryUrl).thenAccept(canary::add));
            inflight.add(probe(stableUrl).thenAccept(stable::add));
            if (canary.size() >= EARLY_ABORT_SAMPLES && Stats.of(canary).errorRate() >= EARLY_ABORT_ERROR_RATE) {
                early = "error rate over " + percent(EARLY_ABORT_ERROR_RATE) + " after " + canary.size() + " requests";
                break;
            }
            sleep(settings.getIntervalMillis());
        }
        CompletableFuture.allOf(inflight.toArray(CompletableFuture[]::new)).join();

        Stats newer = Stats.of(canary);
        Stats older = Stats.of(stable);
        String pods = podProblem(namespace, app, plan.target(), restartsBefore);
        String reason = early != null ? early : decide(newer, older, pods);
        String summary = "new " + newer + " / old " + older;
        if (reason == null) {
            stage.accept(DeployStages.CANARY_ANALYSIS, "canary: PASS " + summary);
            return Verdict.passed(summary);
        }
        stage.accept(DeployStages.CANARY_ANALYSIS, "canary: FAIL " + reason + " — " + summary);
        return Verdict.rejected(reason, summary);
    }

    /** canary Ingress 와 Service 를 지운다. 없으면 넘어가고, API 가 잠깐 실패하면 몇 번 다시 시도한다 */
    public void cleanup(String namespace, String app, List<String> logs) {
        KubernetesClientException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                k8sClient.network().v1().ingresses().inNamespace(namespace).withName(canaryIngressName(app)).delete();
                k8sClient.services().inNamespace(namespace).withName(canaryServiceName(app)).delete();
                return;
            } catch (KubernetesClientException e) {
                last = e;
                sleep(200L * attempt);
            }
        }
        log.warn("canary cleanup failed. app={} message={}", app, last.getMessage());
        logs.add("canary: cleanup failed — " + last.getMessage());
    }

    /** @return 판정 실패 이유. 통과면 null */
    String decide(Stats newer, Stats older, String pods) {
        if (pods != null) {
            return pods;
        }
        if (newer.total() < settings.getMinSamples()) {
            return "too few responses " + newer.total() + " < " + settings.getMinSamples();
        }
        if (newer.errorRate() > settings.getMaxErrorRate()) {
            return "error rate " + percent(newer.errorRate()) + " > " + percent(settings.getMaxErrorRate());
        }
        if (newer.p95() > settings.getMaxP95Millis()) {
            return "p95 " + newer.p95() + "ms > " + settings.getMaxP95Millis() + "ms";
        }
        // 이전 버전이 정상으로 답할 때만 상대 비교를 한다
        if (older.total() >= settings.getMinSamples() && older.errorRate() <= settings.getMaxErrorRate()
                && newer.p95() > older.p95() * settings.getMaxP95Ratio()
                && newer.p95() - older.p95() >= settings.getMinP95RegressionMillis()) {
            return "p95 " + newer.p95() + "ms > " + settings.getMaxP95Ratio() + "x old " + older.p95() + "ms";
        }
        return null;
    }

    private String skipReason(String namespace, String app, String host, SlotPlan plan) {
        if (!settings.isEnabled()) {
            return "disabled";
        }
        if (plan.previous() == null || plan.previous().isBlank()) {
            return "first release";
        }
        Deployment previous = k8sClient.apps().deployments().inNamespace(namespace)
                .withName(app + "-" + plan.previous()).get();
        if (previous == null) {
            return "first release";
        }
        Integer ready = previous.getStatus() == null ? null : previous.getStatus().getReadyReplicas();
        if (ready == null || ready < 1) {
            return "previous " + plan.previous() + " has no ready pods";
        }
        Ingress main = k8sClient.network().v1().ingresses().inNamespace(namespace).withName(app + "-ingress").get();
        boolean sameHost = main != null && main.getSpec() != null && main.getSpec().getRules() != null
                && main.getSpec().getRules().stream().anyMatch(rule -> host.equals(rule.getHost()));
        if (!sameHost) {
            return "no ingress for " + host + " yet";
        }
        return null;
    }

    private void createService(DeployContext context, SlotPlan plan) {
        String namespace = context.namespace();
        String app = context.appName();
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(canaryServiceName(app))
                    .withNamespace(namespace)
                    .addToLabels("app", app)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", app, "color", plan.target()))
                    .addNewPort()
                        .withName("http")
                        .withPort(SERVICE_PORT)
                        .withTargetPort(new IntOrString(context.targetPort()))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
        k8sClient.services().inNamespace(namespace).resource(service).createOrReplace();
    }

    /** 한 번 응답(5xx 포함)을 받을 때까지 최대 WARM_UP_MILLIS. 연결이 안 되는 동안만 기다린다 */
    private void warmUp(String url) {
        long deadline = System.nanoTime() + WARM_UP_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(settings.getRequestTimeoutMillis())).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return;
            } catch (java.io.IOException e) {
                sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("canary 판정이 중단됐습니다", e);
            }
        }
    }

    private CompletableFuture<Sample> probe(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(settings.getRequestTimeoutMillis()))
                .GET()
                .build();
        long start = System.nanoTime();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, error) -> {
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    boolean failed = error != null || response.statusCode() >= 500;
                    return new Sample(millis, failed);
                });
    }

    private int restarts(String namespace, String app, String color) {
        return pods(namespace, app, color).stream()
                .filter(pod -> pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null)
                .flatMap(pod -> pod.getStatus().getContainerStatuses().stream())
                .mapToInt(status -> status.getRestartCount() == null ? 0 : status.getRestartCount())
                .sum();
    }

    private String podProblem(String namespace, String app, String color, int restartsBefore) {
        int after = restarts(namespace, app, color);
        if (after > restartsBefore) {
            return "new pods restarted " + (after - restartsBefore) + " times";
        }
        Deployment target = k8sClient.apps().deployments().inNamespace(namespace).withName(app + "-" + color).get();
        if (target == null || target.getStatus() == null) {
            return "new deployment disappeared";
        }
        int desired = Objects.requireNonNullElse(target.getSpec().getReplicas(), 1);
        int ready = Objects.requireNonNullElse(target.getStatus().getReadyReplicas(), 0);
        if (ready < desired) {
            return "new pods not ready " + ready + "/" + desired;
        }
        return null;
    }

    private List<Pod> pods(String namespace, String app, String color) {
        return k8sClient.pods().inNamespace(namespace).withLabels(Map.of("app", app, "color", color)).list().getItems();
    }

    private String url(String service, String namespace, String path) {
        return "http://" + service + "." + namespace + "." + clusterDomain + ":" + SERVICE_PORT + path;
    }

    static String canaryServiceName(String app) {
        return app + "-canary-svc";
    }

    static String canaryIngressName(String app) {
        return app + "-canary-ingress";
    }

    private static String percent(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("canary 판정이 중단됐습니다", e);
        }
    }

    record Sample(long millis, boolean failed) {
    }

    /** 응답 수, 에러율, p95(ms). 실패한 요청도 걸린 시간에 넣는다 */
    record Stats(int total, double errorRate, long p95) {

        static Stats of(List<Sample> samples) {
            List<Sample> copy = List.copyOf(samples);
            if (copy.isEmpty()) {
                return new Stats(0, 0, 0);
            }
            long errors = copy.stream().filter(Sample::failed).count();
            List<Long> millis = new ArrayList<>(copy.stream().map(Sample::millis).toList());
            Collections.sort(millis);
            int index = (int) Math.ceil(millis.size() * 0.95) - 1;
            return new Stats(copy.size(), (double) errors / copy.size(), millis.get(Math.max(0, index)));
        }

        @Override
        public String toString() {
            return total + " req, error " + percent(errorRate) + ", p95 " + p95 + "ms";
        }
    }

    /**
     * @param outcome PASSED / REJECTED / SKIPPED
     * @param reason  실패·건너뜀 이유
     * @param summary 새 버전과 이전 버전 측정값
     */
    public record Verdict(String outcome, String reason, String summary) {
        static Verdict passed(String summary) {
            return new Verdict("PASSED", null, summary);
        }

        static Verdict rejected(String reason, String summary) {
            return new Verdict("REJECTED", reason, summary);
        }

        static Verdict skipped(String reason) {
            return new Verdict("SKIPPED", reason, null);
        }

        public boolean rejected() {
            return "REJECTED".equals(outcome);
        }

        public boolean ran() {
            return !"SKIPPED".equals(outcome);
        }
    }
}
