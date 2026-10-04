package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 배포된 앱의 활성 슬롯(blue-green 의 color, canary 의 track) 상태 조회와 레플리카 조정, 중지·다시 시작·삭제.
 * 클라우드 버스팅에서 대기 슬롯을 0 으로 두었다가 부하가 오면 올리는 데 쓴다.
 * 새 배포를 하면 슬롯 레플리카는 다시 기본값으로 돌아간다 (중지한 앱도 다시 배포하면 뜬다).
 */
@RestController
@RequestMapping("/api/apps")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    /** 계정당 DB 커넥션 제한(20) / 앱 풀 크기(3) 안에 들어오도록 */
    private static final int MAX_REPLICAS = 5;
    /** Pod 템플릿 어노테이션. DB 를 바꾼 출처(copy|pc)와 시각. 바뀌면 Pod 를 다시 띄운다 */
    static final String DATABASE_SOURCE = "lily.io/database-source";

    private final KubernetesClient k8s;
    private final DeployProperties properties;
    private final DeployLock lock;
    private final AppRemover remover;

    public AppController(KubernetesClient k8s, DeployProperties properties, DeployLock lock, AppRemover remover) {
        this.k8s = k8s;
        this.properties = properties;
        this.lock = lock;
        this.remover = remover;
    }

    @GetMapping("/{appName}")
    public ResponseEntity<AppStatus> status(@PathVariable String appName,
                                            @RequestParam(required = false) String namespace) {
        String ns = namespace(namespace);
        String slot = activeSlot(ns, appName);
        if (slot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statusOf(ns, appName, slot));
    }

    @PutMapping("/{appName}/replicas")
    public ResponseEntity<AppStatus> scale(@PathVariable String appName,
                                           @RequestParam(required = false) String namespace,
                                           @Valid @RequestBody ScaleRequest request) {
        String ns = namespace(namespace);
        String slot = activeSlot(ns, appName);
        if (slot == null) {
            return ResponseEntity.notFound().build();
        }
        String deployment = appName + "-" + slot;
        k8s.apps().deployments().inNamespace(ns).withName(deployment).scale(request.replicas());
        log.info("scaled app={} deployment={} replicas={}", appName, deployment, request.replicas());
        return ResponseEntity.ok(statusOf(ns, appName, slot));
    }

    /**
     * 앱의 모든 슬롯을 0 으로 줄인다. Service, Ingress, DB, 릴리스 기록은 남아서 {@code start} 로 바로 되살린다.
     * 배포·롤백 중이면 409.
     */
    @PostMapping("/{appName}/stop")
    public ResponseEntity<AppStatus> stop(@PathVariable String appName,
                                          @RequestParam(required = false) String namespace) {
        String ns = namespace(namespace);
        String slot = activeSlot(ns, appName);
        if (slot == null) {
            return ResponseEntity.notFound().build();
        }
        try (DeployLock.Handle ignored = lock.acquire(ns, appName, "stop")) {
            List<Deployment> slots = k8s.apps().deployments().inNamespace(ns).withLabel("app", appName).list().getItems();
            for (Deployment d : slots) {
                k8s.apps().deployments().inNamespace(ns).withName(d.getMetadata().getName()).scale(0);
            }
            log.info("stopped app={} slots={}", appName, slots.size());
        }
        return ResponseEntity.ok(statusOf(ns, appName, slot));
    }

    /** 트래픽을 받는 슬롯을 기본 레플리카(lily.deploy.replicas)로 되돌린다. 배포·롤백 중이면 409 */
    @PostMapping("/{appName}/start")
    public ResponseEntity<AppStatus> start(@PathVariable String appName,
                                           @RequestParam(required = false) String namespace) {
        String ns = namespace(namespace);
        String slot = activeSlot(ns, appName);
        if (slot == null) {
            return ResponseEntity.notFound().build();
        }
        int replicas = Math.max(1, Math.min(MAX_REPLICAS, properties.getReplicas()));
        try (DeployLock.Handle ignored = lock.acquire(ns, appName, "start")) {
            k8s.apps().deployments().inNamespace(ns).withName(appName + "-" + slot).scale(replicas);
            log.info("started app={} slot={} replicas={}", appName, slot, replicas);
        }
        return ResponseEntity.ok(statusOf(ns, appName, slot));
    }

    /**
     * 활성 슬롯의 DB 접속 정보를 바꾸고 Pod 를 다시 띄운다. 온프레미스 PC 장애 때 builder 가 대기 슬롯을
     * 내 PC DB(역방향 터널)에서 클라우드 사본(RDS)으로 돌릴 때 쓴다. 원래 값은 {@code {슬롯}-db-pc} 에 한 번만 남겨
     * {@code DELETE} 로 되돌린다. 슬롯 Secret 에 이미 있는 키만 바꾼다 (컨테이너는 그 키만 읽는다).
     * Secret 이 없으면 409, 바꿀 키가 없으면 400, 배포·롤백 중이면 409.
     */
    @PutMapping("/{appName}/database")
    public ResponseEntity<AppStatus> switchDatabase(@PathVariable String appName,
                                                    @RequestParam(required = false) String namespace,
                                                    @Valid @RequestBody DatabaseRequest request) {
        String ns = namespace(namespace);
        String slot = activeSlot(ns, appName);
        if (slot == null) {
            return ResponseEntity.notFound().build();
        }
        String deployment = appName + "-" + slot;
        try (DeployLock.Handle ignored = lock.acquire(ns, appName, "database")) {
            Secret current = k8s.secrets().inNamespace(ns).withName(DatabaseSecret.name(deployment)).get();
            if (current == null) {
                throw new DeployConflictException(deployment + " 에 DB Secret 이 없다");
            }
            Map<String, String> values = DatabaseSecret.read(current);
            if (k8s.secrets().inNamespace(ns).withName(DatabaseSecret.savedName(deployment)).get() == null) {
                DatabaseSecret.write(k8s, ns, appName, DatabaseSecret.savedName(deployment), values);
            }
            Map<String, String> next = new LinkedHashMap<>(values);
            int replaced = 0;
            for (String key : values.keySet()) {
                String value = request.env().get(key);
                if (value != null && !value.isBlank()) {
                    next.put(key, value);
                    replaced++;
                }
            }
            if (replaced == 0) {
                throw new IllegalArgumentException("바꿀 DB 접속 키가 없다: " + values.keySet());
            }
            DatabaseSecret.write(k8s, ns, appName, DatabaseSecret.name(deployment), next);
            restart(ns, deployment, "copy");
            log.info("database switched to copy: app={} deployment={} keys={}", appName, deployment, replaced);
        }
        return ResponseEntity.ok(statusOf(ns, appName, slot));
    }

    /** {@link #switchDatabase} 전의 값으로 되돌리고 Pod 를 다시 띄운다. 바꾼 적이 없으면 그대로 200 */
    @DeleteMapping("/{appName}/database")
    public ResponseEntity<AppStatus> restoreDatabase(@PathVariable String appName,
                                                     @RequestParam(required = false) String namespace) {
        String ns = namespace(namespace);
        String slot = activeSlot(ns, appName);
        if (slot == null) {
            return ResponseEntity.notFound().build();
        }
        String deployment = appName + "-" + slot;
        try (DeployLock.Handle ignored = lock.acquire(ns, appName, "database")) {
            Secret saved = k8s.secrets().inNamespace(ns).withName(DatabaseSecret.savedName(deployment)).get();
            if (saved != null) {
                DatabaseSecret.write(k8s, ns, appName, DatabaseSecret.name(deployment), DatabaseSecret.read(saved));
                k8s.secrets().inNamespace(ns).withName(DatabaseSecret.savedName(deployment)).delete();
                restart(ns, deployment, "pc");
                log.info("database restored: app={} deployment={}", appName, deployment);
            }
        }
        return ResponseEntity.ok(statusOf(ns, appName, slot));
    }

    /** Pod 템플릿 어노테이션을 바꿔 새 Pod 로 갈아 끼운다 (Secret 값은 Pod 가 뜰 때 읽는다) */
    private void restart(String ns, String deployment, String source) {
        k8s.apps().deployments().inNamespace(ns).withName(deployment).edit(d -> new DeploymentBuilder(d)
                .editSpec().editTemplate().editOrNewMetadata()
                    .addToAnnotations(DATABASE_SOURCE, source + "@" + java.time.Instant.now())
                .endMetadata().endTemplate().endSpec()
                .build());
    }

    /**
     * 앱을 클러스터에서 지운다. {@code database=true} 면 DB 도 DROP 한다 (되돌릴 수 없다).
     * 아무것도 없으면 404, 배포·롤백 중이면 409.
     */
    @DeleteMapping("/{appName}")
    public ResponseEntity<AppRemover.Removal> delete(@PathVariable String appName,
                                                     @RequestParam(required = false) String namespace,
                                                     @RequestParam(defaultValue = "false") boolean database) {
        AppRemover.Removal removal = remover.remove(namespace(namespace), appName, database);
        if (removal.nothing()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(removal);
        }
        return ResponseEntity.ok(removal);
    }

    /**
     * Service selector 가 가리키는 슬롯. blue-green 은 color, canary 전략은 track (stable 과 canary 를 번갈아 쓴다).
     * Service 가 없거나 둘 다 없으면 null
     */
    private String selectedSlot(String ns, String appName) {
        Service service = k8s.services().inNamespace(ns).withName(appName + "-svc").get();
        if (service == null || service.getSpec() == null || service.getSpec().getSelector() == null) {
            return null;
        }
        Map<String, String> selector = service.getSpec().getSelector();
        return selector.containsKey("color") ? selector.get("color") : selector.get("track");
    }

    /** 트래픽을 받는 슬롯. Service 가 아직 없는 canary 첫 배포는 stable. 앱이 없으면 null */
    private String activeSlot(String ns, String appName) {
        String slot = selectedSlot(ns, appName);
        if (slot != null) {
            return slot;
        }
        return k8s.apps().deployments().inNamespace(ns).withName(appName + "-stable").get() == null ? null : "stable";
    }

    private AppStatus statusOf(String ns, String appName, String color) {
        Deployment d = k8s.apps().deployments().inNamespace(ns).withName(appName + "-" + color).get();
        int desired = d == null || d.getSpec().getReplicas() == null ? 0 : d.getSpec().getReplicas();
        int ready = d == null || d.getStatus() == null || d.getStatus().getReadyReplicas() == null
                ? 0 : d.getStatus().getReadyReplicas();
        return new AppStatus(appName, ns, color, desired, ready);
    }

    private String namespace(String requested) {
        return requested == null || requested.isBlank() ? properties.getNamespace() : requested;
    }

    public record DatabaseRequest(@NotNull Map<String, String> env) {
    }

    public record ScaleRequest(@NotNull @Min(0) @Max(MAX_REPLICAS) Integer replicas) {
    }

    public record AppStatus(String appName, String namespace, String activeColor, int replicas, int readyReplicas) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(DeployConflictException.class)
    ResponseEntity<Map<String, String>> conflict(DeployConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "REJECTED", "message", String.valueOf(e.getMessage())));
    }
}
