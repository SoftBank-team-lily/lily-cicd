package com.lily.cicd.deploy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 앱별로 진행 중이거나 마지막으로 끝난 배포의 단계. 배포 요청은 끝날 때까지 응답하지 않으므로
 * lily-builder 가 진행 상황을 보여 주려면 따로 물어야 한다. 메모리에만 둔다 (재시작하면 비어 있다).
 */
@org.springframework.stereotype.Component
public class DeployProgress {

    private final Map<String, Snapshot> latest = new ConcurrentHashMap<>();

    void update(String namespace, String appName, String stage, String detail) {
        latest.put(key(namespace, appName), new Snapshot(stage, detail, Instant.now()));
    }

    public Optional<Snapshot> get(String namespace, String appName) {
        return Optional.ofNullable(latest.get(key(namespace, appName)));
    }

    private static String key(String namespace, String appName) {
        return namespace + "/" + appName;
    }

    /** @param stage {@link com.lily.cicd.module.DeployStages} 값 */
    public record Snapshot(String stage, String detail, Instant updatedAt) {
    }
}
