package com.lily.cicd.deploy;

import io.fabric8.kubernetes.api.model.LifecycleBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;

import java.util.List;

/**
 * 트래픽을 빼는 동안 프로세스를 살려 둔다.
 * nginx 가 엔드포인트에서 이전 Pod 를 지우기 전에 컨테이너가 죽으면 502 가 난다.
 */
final class SlotPods {

    private SlotPods() {
    }

    /** drain 이 0 이면 아무 것도 하지 않는다. grace 는 preStop 이 끝난 뒤 앱이 종료할 시간을 남긴다 */
    static void preStop(PodSpec spec, int drainSeconds) {
        if (drainSeconds <= 0 || spec.getContainers() == null || spec.getContainers().isEmpty()) {
            return;
        }
        spec.setTerminationGracePeriodSeconds(30L + drainSeconds);
        spec.getContainers().get(0).setLifecycle(new LifecycleBuilder()
                .withNewPreStop()
                    .withNewExec()
                        .withCommand("sleep", Integer.toString(drainSeconds))
                    .endExec()
                .endPreStop()
                .build());
    }

    /** replica 를 0 으로 만들기 전에 호출한다. 이전 Pod 는 아직 Ready 인 채로 엔드포인트가 새 슬롯으로 옮겨 간다 */
    static void waitBeforeRemove(int drainSeconds, List<String> logs) {
        if (drainSeconds <= 0) {
            return;
        }
        logs.add("drain: " + drainSeconds + "s before scale down");
        try {
            Thread.sleep(drainSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeploymentFailedException("drain 대기 중 중단됨", logs, e);
        }
    }
}
