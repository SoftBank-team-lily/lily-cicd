package com.lily.cicd.deploy;

import java.util.List;

/**
 * 배포나 롤백 한 번이 끝났을 때의 결과.
 *
 * @param status        배포 성공 {@code SUCCESS}. 롤백은 {@code ROLLED_BACK}, 앱만 되돌리고 스키마는 실패하면 {@code PARTIAL}
 * @param activeColor   지금 트래픽을 받는 슬롯. blue-green 은 blue 또는 green, canary 는 stable 또는 canary
 * @param targetHostUrl Ingress 호스트. 예: {@code http://lily.domain.com}
 * @param schemaVersion 끝났을 때의 스키마 버전. 플랫폼이 스키마를 맡지 않는 앱이면 null
 * @param logs          단계별 실행 기록. 실패 원인은 {@link DeploymentFailedException#getLogs()} 에 있다.
 */
public record DeploymentResultDto(
        String status,
        String activeColor,
        String targetHostUrl,
        String schemaVersion,
        List<String> logs
) {
    public DeploymentResultDto(String status, String activeColor, String targetHostUrl, List<String> logs) {
        this(status, activeColor, targetHostUrl, null, logs);
    }
}
