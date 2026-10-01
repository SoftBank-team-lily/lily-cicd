package com.lily.cicd.deploy;

import com.lily.cicd.module.DeployStages;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 앱별로 진행 중이거나 마지막으로 끝난 배포의 단계.
 * 배포 요청은 끝날 때까지 응답하지 않으므로 lily-builder 가 진행 상황을 따로 묻는다.
 *
 * <p>이 프로세스의 메모리와 ConfigMap {@code lily-progress-{app}} 에 같이 남긴다.
 * Pod 가 죽어도 마지막 단계와 시각은 남고, 살아 있는 동안에는 몇 초마다 시각만 갱신한다.
 * 갱신이 멈추면 배포 스레드가 죽었다는 뜻이다. builder 는 POST 를 다시 보내지 않고 이 기록만 본다.
 */
@org.springframework.stereotype.Component
public class DeployProgress {

    static final Duration PULSE = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(DeployProgress.class);

    private final Map<String, Snapshot> latest = new ConcurrentHashMap<>();
    /** 이 프로세스가 update 한 키만 맥박을 갱신한다. 재시작 뒤 읽어 온 기록은 죽은 배포다 */
    private final Set<String> live = ConcurrentHashMap.newKeySet();
    private final KubernetesClient k8s;

    @Autowired
    public DeployProgress(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    /** 테스트. 클러스터에 남기지 않는다 */
    public DeployProgress() {
        this(null);
    }

    void update(String namespace, String appName, String stage, String detail, String image) {
        Snapshot snapshot = new Snapshot(stage, detail, Instant.now(), image);
        String key = key(namespace, appName);
        latest.put(key, snapshot);
        live.add(key);
        write(namespace, appName, snapshot);
    }

    /**
     * 같은 단계의 시각만 앞으로 당긴다. 끝난 단계는 건드리지 않는다.
     * 이 프로세스가 시작한 배포만 해당한다.
     */
    void touch(String namespace, String appName) {
        String key = key(namespace, appName);
        if (!live.contains(key)) {
            return;
        }
        Snapshot current = latest.get(key);
        if (current == null || terminal(current.stage())) {
            return;
        }
        Snapshot refreshed = new Snapshot(current.stage(), current.detail(), Instant.now(), current.image());
        latest.put(key, refreshed);
        write(namespace, appName, refreshed);
    }

    /** 배포가 도는 동안 시각을 갱신한다. 프로세스가 죽으면 멈춘다 */
    com.lily.cicd.release.DeployLock.Beat pulse(String namespace, String appName) {
        AtomicBoolean stopped = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().name("progress-" + appName).start(() -> {
            while (!stopped.get()) {
                try {
                    Thread.sleep(PULSE.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!stopped.get()) {
                    touch(namespace, appName);
                }
            }
        });
        return () -> {
            stopped.set(true);
            thread.interrupt();
        };
    }

    /** 앱을 지운 뒤. 기록 ConfigMap 은 지우는 쪽이 같이 지운다 */
    public void forget(String namespace, String appName) {
        String key = key(namespace, appName);
        latest.remove(key);
        live.remove(key);
    }

    public Optional<Snapshot> get(String namespace, String appName) {
        Snapshot cached = latest.get(key(namespace, appName));
        if (cached != null) {
            return Optional.of(cached);
        }
        return Optional.ofNullable(read(namespace, appName));
    }

    private void write(String namespace, String appName, Snapshot snapshot) {
        if (k8s == null) {
            return;
        }
        Map<String, String> data = new HashMap<>();
        data.put("stage", snapshot.stage() == null ? "" : snapshot.stage());
        data.put("detail", snapshot.detail() == null ? "" : snapshot.detail());
        data.put("updatedAt", snapshot.updatedAt().toString());
        if (snapshot.image() != null && !snapshot.image().isBlank()) {
            data.put("image", snapshot.image());
        }
        try {
            k8s.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                    .withNewMetadata()
                        .withName(configMapName(appName))
                        .withNamespace(namespace)
                        .addToLabels("app", appName)
                        .addToLabels("lily.io/progress", "true")
                    .endMetadata()
                    .withData(data)
                    .build()).createOrReplace();
        } catch (RuntimeException e) {
            log.warn("progress persist failed. app={} message={}", appName, e.getMessage());
        }
    }

    private Snapshot read(String namespace, String appName) {
        if (k8s == null) {
            return null;
        }
        try {
            ConfigMap map = k8s.configMaps().inNamespace(namespace).withName(configMapName(appName)).get();
            if (map == null || map.getData() == null || map.getData().get("stage") == null
                    || map.getData().get("stage").isBlank()) {
                return null;
            }
            Map<String, String> data = map.getData();
            Instant updated = Instant.parse(data.get("updatedAt"));
            String image = data.get("image");
            return new Snapshot(data.get("stage"), data.get("detail"), updated,
                    image == null || image.isBlank() ? null : image);
        } catch (RuntimeException e) {
            log.warn("progress read failed. app={} message={}", appName, e.getMessage());
            return null;
        }
    }

    static String configMapName(String appName) {
        return "lily-progress-" + appName;
    }

    private static boolean terminal(String stage) {
        return DeployStages.SUCCEEDED.equals(stage) || DeployStages.FAILED.equals(stage);
    }

    private static String key(String namespace, String appName) {
        return namespace + "/" + appName;
    }

    /**
     * @param stage {@link DeployStages} 값
     * @param image 이번 배포 이미지. builder 가 자기 빌드인지 가릴 때 쓴다
     */
    public record Snapshot(String stage, String detail, Instant updatedAt, String image) {
    }
}
