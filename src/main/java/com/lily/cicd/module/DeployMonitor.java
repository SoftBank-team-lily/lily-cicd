package com.lily.cicd.module;

/**
 * Monitoring 모듈이 구현한다.
 * 트래픽 전환과 Router 연결이 끝난 뒤 {@link #attached} 를 호출한다.
 * 스크랩 대상은 {@code context.host()} 가 아니라 클러스터 안의
 * {@code context.serviceName()}:{@code context.targetPort()}{@code context.metricsPath()} 다.
 *
 * <p>이 호출이 실패해도 배포 결과는 성공으로 남긴다.
 * 구현 빈이 없으면 아무 것도 하지 않는다.
 */
public interface DeployMonitor {

    void attached(DeployContext context);

    void failed(DeployContext context, String message);
}
