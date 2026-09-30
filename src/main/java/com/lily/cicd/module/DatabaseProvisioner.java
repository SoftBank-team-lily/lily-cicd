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
}
