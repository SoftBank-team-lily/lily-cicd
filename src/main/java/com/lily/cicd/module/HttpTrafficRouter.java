package com.lily.cicd.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * lily-router 에 HTTP 로 라우트를 요청한다. cicd 는 Ingress 를 직접 쓰지 않는다.
 * 프로토콜: lily-router docs/api/01-Ingress Nginx API.md
 *
 * <p>lily-router 의 PUT·DELETE 는 멱등이라 연결 실패와 5xx 는 몇 번 다시 보낸다. 4xx 는 다시 보내지 않는다.
 * lily-router 가 멈춰도 이미 쓴 Ingress 는 남으므로 사용자 트래픽에는 영향이 없다.
 */
public final class HttpTrafficRouter implements TrafficRouter {

    private static final Logger log = LoggerFactory.getLogger(HttpTrafficRouter.class);
    static final int ATTEMPTS = 3;
    private static final String ROUTE = "/api/v1/routes/{namespace}/{app}";

    private final RestClient http;
    private final long backoffMillis;

    public HttpTrafficRouter(RestClient.Builder builder, String baseUrl, String apiToken) {
        this(builder, baseUrl, apiToken, 200);
    }

    /** @param backoffMillis 첫 재시도 전 대기. 다음 재시도마다 두 배. 테스트에서 0 으로 둔다 */
    HttpTrafficRouter(RestClient.Builder builder, String baseUrl, String apiToken, long backoffMillis) {
        RestClient.Builder configured = builder.baseUrl(baseUrl);
        if (apiToken != null && !apiToken.isBlank()) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken);
        }
        this.http = configured.build();
        this.backoffMillis = backoffMillis;
    }

    @Override
    public void route(DeployContext context) {
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("slot", context.targetColor());
        deployment.put("version", context.appVersion());
        deployment.put("image", context.imageUrl());
        deployment.put("status", "ACTIVE");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", context.serviceName());
        body.put("servicePort", context.servicePort());
        body.put("host", context.host());
        body.put("deployment", deployment);

        retry("route " + context.appName(), () -> http.put()
                .uri(ROUTE, context.namespace(), context.appName())
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * 라우트가 아직 없으면(404) 먼저 등록하고 다시 연다.
     * canary 전략은 {@link #route} 보다 먼저 canary 를 열고, lily-router 를 붙이기 전에 배포된 앱은 라우트가 없다.
     * 등록할 때 lily-router 가 기존 {@code {app}-ingress} 의 호스트·TLS 를 이어받는다.
     */
    @Override
    public void openCanary(DeployContext context, String canaryService, int weight) {
        Map<String, Object> body = Map.of(
                "serviceName", canaryService,
                "servicePort", context.servicePort(),
                "weight", weight);
        Supplier<Object> put = () -> http.put()
                .uri(ROUTE + "/canary", context.namespace(), context.appName())
                .body(body)
                .retrieve()
                .toBodilessEntity();
        try {
            retry("open canary " + context.appName(), put);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("no route yet, registering before canary. app={}", context.appName());
            route(context);
            retry("open canary " + context.appName(), put);
        }
    }

    @Override
    public void closeCanary(String namespace, String appName) {
        try {
            retry("close canary " + appName, () -> http.delete()
                    .uri(ROUTE + "/canary", namespace, appName)
                    .retrieve()
                    .toBodilessEntity());
        } catch (HttpClientErrorException.NotFound e) {
            // 라우트가 없으면 canary 도 없다
        }
    }

    @Override
    public boolean routes(String namespace, String appName, String host) {
        try {
            RouteView route = retry("find route " + appName, () -> http.get()
                    .uri(ROUTE, namespace, appName)
                    .retrieve()
                    .body(RouteView.class));
            return route != null && route.hosts() != null && route.hosts().contains(host);
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    @Override
    public List<AppRef> openCanaries() {
        List<RouteView> routes = retry("list canaries", () -> http.get()
                .uri("/api/v1/routes?canary=true")
                .retrieve()
                .body(new ParameterizedTypeReference<List<RouteView>>() {}));
        List<AppRef> apps = new ArrayList<>();
        if (routes != null) {
            routes.forEach(route -> apps.add(new AppRef(route.namespace(), route.app())));
        }
        return apps;
    }

    @Override
    public List<String> remove(String namespace, String appName) {
        try {
            retry("remove route " + appName, () -> http.delete()
                    .uri(ROUTE, namespace, appName)
                    .retrieve()
                    .toBodilessEntity());
            return List.of("route/" + appName);
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    /** 연결 실패와 5xx 만 다시 보낸다. 4xx 는 그대로 던진다 */
    private <T> T retry(String what, Supplier<T> call) {
        RuntimeException last = null;
        long wait = backoffMillis;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (HttpServerErrorException | ResourceAccessException e) {
                last = e;
                log.warn("lily-router call failed. what={} attempt={}/{} message={}",
                        what, attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) {
                    sleep(wait);
                    wait *= 2;
                }
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lily-router 재시도 대기가 중단됐다", e);
        }
    }

    /** lily-router 응답 Route 중 cicd 가 쓰는 필드 */
    record RouteView(String namespace, String app, List<String> hosts) {
    }
}
