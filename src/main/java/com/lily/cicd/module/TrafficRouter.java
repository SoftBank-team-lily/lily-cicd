package com.lily.cicd.module;

import java.util.List;

/**
 * Router 모듈이 구현한다. 외부 요청이 앱 Service 로 가는 입구(Ingress)는 이 인터페이스 뒤에서만 바뀐다.
 *
 * <ul>
 *   <li>{@link NginxIngressRouter}: 기본. cicd 가 Ingress 를 직접 쓴다</li>
 *   <li>{@link HttpTrafficRouter}: {@code lily.router.url} 이 있으면. lily-router 에 HTTP 로 요청한다</li>
 * </ul>
 *
 * <p>{@link #route} 만 필수다. 나머지는 canary·복구·삭제에서 쓰며, 구현하지 않으면 아무 것도 하지 않는다.
 */
public interface TrafficRouter {

    /**
     * CICD 가 Service selector 를 새 색으로 바꾼 뒤에 호출된다.
     * 외부 요청이 {@code context.serviceName()} 의 {@code context.servicePort()} 로 가게 하면 된다.
     */
    void route(DeployContext context);

    /**
     * {@code context.host()} 로 들어온 요청 중 {@code weight}% 를 {@code canaryService} 로 보낸다.
     * 같은 호출을 weight 만 바꿔 다시 하면 비율이 바뀐다. 0 이면 입구만 만든다.
     */
    default void openCanary(DeployContext context, String canaryService, int weight) {
    }

    /** canary 입구를 닫는다. 없어도 성공한다 */
    default void closeCanary(String namespace, String appName) {
    }

    /** 이 앱이 이미 {@code host} 로 라우팅되고 있는지. canary 판정 전에 확인한다 */
    default boolean routes(String namespace, String appName, String host) {
        return false;
    }

    /** canary 입구가 열려 있는 앱. cicd 가 재시작한 뒤 남은 입구를 정리할 때 쓴다 */
    default List<AppRef> openCanaries() {
        return List.of();
    }

    /** 앱 삭제. 라우트와 canary 입구를 모두 지운다. 지운 리소스 이름을 돌려준다 (없었으면 빈 목록) */
    default List<String> remove(String namespace, String appName) {
        return List.of();
    }

    record AppRef(String namespace, String appName) {
    }
}
