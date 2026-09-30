package com.lily.cicd.deploy;

import com.lily.cicd.module.DeployContext;

import java.util.List;
import java.util.Map;

/**
 * 새 이미지를 클러스터에 올리고 트래픽을 옮기는 규칙.
 *
 * <p>기본 구현은 {@link BlueGreenDeploymentStrategy} 다.
 * {@code lily.deploy.strategy=canary} 이면 {@link CanaryDeploymentStrategy} 를 쓴다.
 * 같은 타입의 빈을 직접 등록하면 그 구현이 우선한다.
 *
 * <p>엔진이 부르는 순서:
 * DB 준비, {@link #plan}, {@link #applyTarget}, {@link #awaitReady},
 * {@link #switchTraffic}, Router, Monitoring, {@link #retirePrevious}.
 * 각 메서드는 {@code logs} 에 한 줄 이상 남긴다.
 */
public interface DeploymentStrategy {

    /** 로그와 설정에 찍는 전략 이름. 예: {@code blue-green}. */
    String name();

    SlotPlan plan(String namespace, String appName, List<String> logs);

    void applyTarget(
            DeployCommand command,
            String namespace,
            SlotPlan plan,
            Map<String, String> databaseEnv,
            List<String> logs);

    void awaitReady(String namespace, String appName, SlotPlan plan, List<String> logs);

    void switchTraffic(DeployCommand command, String namespace, SlotPlan plan, List<String> logs);

    void retirePrevious(String namespace, String appName, SlotPlan plan, List<String> logs);

    /**
     * Router 와 Monitoring 에 넘기기 전에 슬롯 이름을 context 에 심는다.
     * 기본은 {@code plan.target()} 을 {@code targetColor} 에 넣는다.
     */
    default DeployContext bind(DeployContext context, SlotPlan plan) {
        return context.withTargetColor(plan.target());
    }
}
