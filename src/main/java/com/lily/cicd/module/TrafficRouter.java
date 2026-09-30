package com.lily.cicd.module;

/**
 * Router 모듈이 구현한다.
 * CICD 가 Service selector 를 새 색으로 바꾼 뒤에 호출된다.
 * 외부 요청이 {@code context.serviceName()} 의 {@code context.servicePort()} 로 가게 하면 된다.
 *
 * <p>구현 빈이 없으면 Nginx Ingress 로 {@code {appName}-ingress} 를 만든다.
 */
public interface TrafficRouter {

    void route(DeployContext context);
}
