package com.lily.cicd.deploy;

import java.util.List;

/**
 * 블루그린 한 번이 끝났을 때의 결과.
 *
 * @param status        성공 시 {@code SUCCESS}
 * @param activeColor   트래픽이 향하는 색. {@code blue} 또는 {@code green}
 * @param targetHostUrl Ingress 호스트. 예: {@code http://lily.domain.com}
 * @param logs          단계별 실행 기록. 실패 원인은 {@link DeploymentFailedException#getLogs()} 에 있다.
 */
public record DeploymentResultDto(
        String status,
        String activeColor,
        String targetHostUrl,
        List<String> logs
) {
}
