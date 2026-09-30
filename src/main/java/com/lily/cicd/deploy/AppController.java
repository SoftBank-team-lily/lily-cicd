package com.lily.cicd.deploy;

import com.lily.cicd.config.DeployProperties;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 배포된 앱의 활성 슬롯(blue-green) 상태 조회와 레플리카 조정.
 * 클라우드 버스팅에서 대기 슬롯을 0 으로 두었다가 부하가 오면 올리는 데 쓴다.
 * 새 배포를 하면 슬롯 레플리카는 다시 기본값으로 돌아간다.
 */
@RestController
@RequestMapping("/api/apps")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    /** 계정당 DB 커넥션 제한(20) / 앱 풀 크기(3) 안에 들어오도록 */
    private static final int MAX_REPLICAS = 5;

    private final KubernetesClient k8s;
    private final DeployProperties properties;

    public AppController(KubernetesClient k8s, DeployProperties properties) {
        this.k8s = k8s;
        this.properties = properties;
    }

    @GetMapping("/{appName}")
    public ResponseEntity<AppStatus> status(@PathVariable String appName,
                                            @RequestParam(required = false) String namespace) {
        String ns = namespace(namespace);
        String color = activeColor(ns, appName);
        if (color == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statusOf(ns, appName, color));
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

    /** blue-green 의 Service selector 에 있는 color. Service 가 없거나 color 가 없으면 null */
    private String activeColor(String ns, String appName) {
        Service service = k8s.services().inNamespace(ns).withName(appName + "-svc").get();
        if (service == null || service.getSpec() == null || service.getSpec().getSelector() == null) {
            return null;
        }
        return service.getSpec().getSelector().get("color");
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
}
