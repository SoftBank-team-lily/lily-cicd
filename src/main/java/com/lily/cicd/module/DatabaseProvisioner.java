package com.lily.cicd.module;

import java.util.Map;

/**
 * DB 모듈이 구현한다.
 * 새 슬롯이 뜨기 전에 호출된다. blue 와 green 은 같은 DB 를 쓴다.
 *
 * <p>반환 맵은 컨테이너 환경변수로 들어간다.
 * 요청의 extraEnv 가 같은 키를 주면 extraEnv 가 이긴다.
 * {@code APP_COLOR}, {@code APP_VERSION}, {@code SERVER_PORT} 는 CICD 가 마지막에 덮어쓴다.
 * 구현 빈이 없으면 빈 맵을 반환해서 배포는 계속된다.
 */
public interface DatabaseProvisioner {

    Map<String, String> prepare(DeployContext context);

    /**
     * 앱을 지울 때 그 앱의 DB 도 지운다. 되돌릴 수 없다.
     *
     * @return 지웠으면 true. DB 가 없거나 DB 모듈이 없으면 false
     */
    default boolean release(String appName) {
        return false;
    }

    /**
     * 앱 DB 에 pgroll 을 켠다 (상태 스키마와 이벤트 트리거, 관리자 권한 필요). 여러 번 불러도 된다.
     * pgroll 마이그레이션을 적용하기 전에 부른다.
     *
     * @throws IllegalStateException DB 모듈이 pgroll 을 지원하지 않는다
     */
    default void enablePgroll(DeployContext context) {
        throw new IllegalStateException("DB 모듈이 pgroll 을 지원하지 않는다");
    }
}
