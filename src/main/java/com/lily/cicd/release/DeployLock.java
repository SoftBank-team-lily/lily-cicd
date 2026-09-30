package com.lily.cicd.release;

import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * 같은 앱의 배포와 롤백을 한 번에 하나만 돌린다. Lease {@code lily-lock-{app}} 을 만든 쪽이 잡는다.
 *
 * <p>lily-cicd 가 배포 도중 죽으면 Lease 가 남는다. {@link #EXPIRY} 가 지나면 만료로 보고 가져간다.
 */
public class DeployLock {

    static final Duration EXPIRY = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(DeployLock.class);

    private final KubernetesClient k8s;

    public DeployLock(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    /** @throws DeployConflictException 다른 배포나 롤백이 진행 중 */
    public Handle acquire(String namespace, String appName, String purpose) {
        String name = "lily-lock-" + appName;
        String holder = purpose + "/" + UUID.randomUUID().toString().substring(0, 8);
        Lease lease = lease(namespace, name, holder);
        try {
            k8s.leases().inNamespace(namespace).resource(lease).create();
            return new Handle(namespace, name, holder);
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) {
                throw e;
            }
        }
        Lease existing = k8s.leases().inNamespace(namespace).withName(name).get();
        if (existing != null && !expired(existing)) {
            throw new DeployConflictException(
                    appName + " 은 다른 작업이 진행 중이다 (" + existing.getSpec().getHolderIdentity() + ")");
        }
        try {
            // resourceVersion 을 그대로 두고 update 한다. 동시에 가져가려는 쪽이 있으면 한쪽만 성공한다
            if (existing == null) {
                k8s.leases().inNamespace(namespace).resource(lease).create();
            } else {
                log.warn("taking over expired lock. name={} holder={}", name, existing.getSpec().getHolderIdentity());
                lease.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
                k8s.leases().inNamespace(namespace).resource(lease).update();
            }
            return new Handle(namespace, name, holder);
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                throw new DeployConflictException(appName + " 은 다른 작업이 진행 중이다");
            }
            throw e;
        }
    }

    private Lease lease(String namespace, String name, String holder) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return new LeaseBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
                .withNewSpec()
                    .withHolderIdentity(holder)
                    .withLeaseDurationSeconds((int) EXPIRY.toSeconds())
                    .withAcquireTime(now)
                    .withRenewTime(now)
                .endSpec()
                .build();
    }

    private static boolean expired(Lease lease) {
        ZonedDateTime renew = lease.getSpec() == null ? null : lease.getSpec().getRenewTime();
        return renew == null || renew.toInstant().plus(EXPIRY).isBefore(Instant.now());
    }

    /** try-with-resources 로 쓴다. 닫을 때 내가 잡은 Lease 만 지운다 */
    public final class Handle implements AutoCloseable {
        private final String namespace;
        private final String name;
        private final String holder;

        private Handle(String namespace, String name, String holder) {
            this.namespace = namespace;
            this.name = name;
            this.holder = holder;
        }

        @Override
        public void close() {
            try {
                Lease current = k8s.leases().inNamespace(namespace).withName(name).get();
                if (current != null && holder.equals(current.getSpec().getHolderIdentity())) {
                    k8s.leases().inNamespace(namespace).withName(name).delete();
                }
            } catch (KubernetesClientException e) {
                // 남아도 EXPIRY 뒤에 다음 작업이 가져간다
                log.warn("failed to release lock. name={} message={}", name, e.getMessage());
            }
        }
    }
}
