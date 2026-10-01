package com.lily.cicd.module;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * lily-db-provisioner 를 HTTP 로 호출해서 앱 DB 를 준비한다.
 *
 * <p>배포 요청의 database 가 있는 앱만 DB 를 준비한다. appName 을 그대로 projectId 로 쓴다. 앱당 DB 는 하나이고 blue, green 이 같이 쓴다.
 * 배포할 때마다 projectId 로 조회해서 있으면 그대로 쓰고, 없거나 FAILED 면 새로 만든다.
 * 그래서 databaseId 를 따로 저장하지 않는다.
 *
 * <p>반환 맵에는 DB 비밀번호가 들어 있다. 로그나 예외 메시지에 값을 남기지 않는다.
 */
public final class HttpDatabaseProvisioner implements DatabaseProvisioner {

    private static final String AVAILABLE = "AVAILABLE";
    private static final String FAILED = "FAILED";

    private final RestClient http;

    public HttpDatabaseProvisioner(RestClient.Builder builder, String baseUrl, String apiToken) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .build();
    }

    @Override
    public Map<String, String> prepare(DeployContext context) {
        // 배포 요청에 database 가 없으면 DB 가 필요 없는 앱 (프론트엔드 등)
        if (context.database() == null || context.database().isBlank()) {
            return Map.of();
        }
        String projectId = context.appName();
        Database db = findOrCreate(projectId, context.database());
        if (!AVAILABLE.equals(db.status())) {
            throw new IllegalStateException(
                    "database not available: project=" + projectId + " status=" + db.status());
        }
        EnvResponse response = http.get()
                .uri("/api/databases/{id}/env", db.id())
                .retrieve()
                .body(EnvResponse.class);
        if (response == null || response.env() == null) {
            throw new IllegalStateException("database env is empty: project=" + projectId);
        }
        return response.env();
    }

    /** projectId(= appName) 로 찾아 지운다. 프로비저너가 DB 와 계정을 DROP 하고 비밀번호도 지운다 */
    @Override
    public boolean release(String appName) {
        Optional<Database> existing = find(appName);
        if (existing.isEmpty()) {
            return false;
        }
        http.delete().uri("/api/databases/{id}", existing.get().id()).retrieve().toBodilessEntity();
        return true;
    }

    private Database findOrCreate(String projectId, String engine) {
        Optional<Database> existing = find(projectId);
        // FAILED 는 같은 projectId 로 다시 POST 하면 프로비저너가 정리하고 새로 만든다
        if (existing.isPresent() && !FAILED.equals(existing.get().status())) {
            return existing.get();
        }
        try {
            return http.post()
                    .uri("/api/databases")
                    .body(Map.of("projectId", projectId, "engine", engine))
                    .retrieve()
                    .body(Database.class);
        } catch (HttpClientErrorException.Conflict e) {
            // 같은 앱 배포가 동시에 들어와 다른 요청이 먼저 만든 경우
            return find(projectId).orElseThrow(() -> e);
        }
    }

    private Optional<Database> find(String projectId) {
        List<Database> found = http.get()
                .uri(uri -> uri.path("/api/databases").queryParam("projectId", projectId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Database>>() {});
        return found == null ? Optional.empty() : found.stream().findFirst();
    }

    record Database(String id, String projectId, String status) {}

    record EnvResponse(String databaseId, Map<String, String> env) {}
}
