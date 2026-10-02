package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import com.lily.cicd.module.OnPremUpstream;
import com.lily.cicd.release.DeployConflictException;
import com.lily.cicd.release.DeployLock;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
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

import java.util.List;
import java.util.Map;

/**
 * 배포된 앱의 활성 슬롯(blue-green) 상태 조회와 레플리카 조정, 중지·다시 시작·삭제.
 * 클라우드 버스팅에서 대기 슬롯을 0 으로 두었다가 부하가 오면 올리는 데 쓴다.
 * 새 배포를 하면 슬롯 레플리카는 다시 기본값으로 돌아간다 (중지한 앱도 다시 배포하면 뜬다).
 *
 * <p>{@code /upstream}: 클라우드 앱의 공개 주소는 두고 Ingress 가 온프레미스 공개 주소로 넘기게 하거나 되돌린다
 * ({@link OnPremUpstream}). 클라우드 앱을 사용자 PC 로 옮길 때 쓴다.
 */
@RestController
@RequestMapping("/api/apps")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    /** 계정당 DB 커넥션 제한(20) / 앱 풀 크기(3) 안에 들어오도록 */
    private static final int MAX_REPLICAS = 5;

    private final KubernetesClient k8s;
    private final DeployProperties properties;
    private final DeployLock lock;
    private final AppRemover remover;
    private final OnPremUpstream upstream;

    public AppController(KubernetesClient k8s, DeployProperties properties, DeployLock lock, AppRemover remover) {
        this.k8s = k8s;
        this.properties = properties;
        this.lock = lock;
        this.remover = remover;
        this.upstream = new OnPremUpstream(k8s);
    }

    /** Ingress 가 지금 넘기는 온프레미스 호스트. 넘기지 않으면 upstream 이 null */
    @GetMapping("/{appName}/upstream")
    public ResponseEntity<Upstream> upstream(@PathVariable String appName,
                                             @RequestParam(required = false) String namespace) {
        return ResponseEntity.ok(new Upstream(appName, upstream.current(namespace(namespace), appName).orElse(null)));
    }

    /** 앱 Ingress 를 온프레미스 공개 주소로 넘긴다. Ingress 가 없으면 404, 배포·롤백 중이면 409 */
    @PutMapping("/{appName}/upstream")
    public ResponseEntity<Upstream> pointUpstream(@PathVariable String appName,
                                                  @RequestParam(required = false) String namespace,
                                                  @Valid @RequestBody UpstreamRequest request) {
        String ns = namespace(namespace);
        try (DeployLock.Handle ignored = lock.acquire(ns, appName, "upstream")) {
            upstream.point(ns, appName, request.host());
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
        log.info("upstream app={} -> onprem {}", appName, request.host());
        return ResponseEntity.ok(new Upstream(appName, upstream.current(ns, appName).orElse(null)));
    }

    /** 앱 Ingress 를 클러스터 Service 로 되돌린다. 넘기지 않았으면 그대로 200 */
    @DeleteMapping("/{appName}/upstream")
    public ResponseEntity<Upstream> restoreUpstream(@PathVariable String appName,
                                                    @RequestParam(required = false) String namespace) {
        String ns = namespace(namespace);
        try (DeployLock.Handle ignored = lock.acquire(ns, appName, "upstream")) {
            if (upstream.restore(ns, appName)) {
                log.info("upstream app={} -> cluster", appName);
            }
        }
        return ResponseEntity.ok(new Upstream(appName, null));
    }

    /** @param upstream 온프레미스 공개 호스트. 클러스터 Service 로 가면 null */
    public record Upstream(String appName, String upstream) {
    }

    public record UpstreamRequest(@NotNull String host) {
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
        String color = activeColor(ns, appName);
        if (color == null) {
            return ResponseEntity.notFound().build();
        }
        String deployment = appName + "-" + color;
        k8s.apps().deployments().inNamespace(ns).withName(deployment).scale(request.replicas());
        log.info("scaled app={} deployment={} replicas={}", appName, deployment, request.replicas());
        return ResponseEntity.ok(statusOf(ns, appName, color));
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

    /** blue-green 의 Service selector 에 있는 color. Service 가 없거나 color 가 없으면 null */
    private String activeColor(String ns, String appName) {
        Service service = k8s.services().inNamespace(ns).withName(appName + "-svc").get();
        if (service == null || service.getSpec() == null || service.getSpec().getSelector() == null) {
            return null;
        }
        return service.getSpec().getSelector().get("color");
    }

    /** 트래픽을 받는 슬롯. blue-green 은 Service 의 color, canary 전략은 stable. 앱이 없으면 null */
    private String activeSlot(String ns, String appName) {
        String color = activeColor(ns, appName);
        if (color != null) {
            return color;
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
