package com.lily.cicd.deploy;

import java.util.Map;

/**
 * 배포 한 건의 입력. 전략과 무관하다.
 * null 인 선택 항목은 {@code DeployProperties} 기본값을 쓴다.
 *
 * @param appName          DNS 라벨. 리소스 이름의 접두사. 기본값 {@code lily}
 * @param imageUrl         ECR 이미지. 예: {@code 123.dkr.ecr.ap-northeast-2.amazonaws.com/lily-blog-sample:1.0.0}
 * @param targetPort       컨테이너가 듣는 포트. lily-blog-sample 은 8080
 * @param appVersion       컨테이너 환경변수 {@code APP_VERSION}. null 이면 {@code dev}
 * @param imagePullSecret  ECR 비공개 이미지용 docker-registry 시크릿 이름. 없으면 null
 * @param extraEnv         추가로 넣을 환경변수. {@code APP_COLOR} 와 {@code SERVER_PORT} 는 엔진이 다시 덮어쓴다
 * @param database         DB 엔진 ({@code postgres} / {@code mysql}). null 이면 DB 를 만들지 않는다
 */
public record DeployCommand(
        String appName,
        String imageUrl,
        int targetPort,
        String namespace,
        String domain,
        String readinessPath,
        String livenessPath,
        String appVersion,
        String imagePullSecret,
        Map<String, String> extraEnv,
        String database
) {
    /** DB 없이 배포 */
    public DeployCommand(
            String appName, String imageUrl, int targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, null);
    }

    public static DeployCommand of(String appName, String imageUrl, int targetPort) {
        return new DeployCommand(
                appName, imageUrl, targetPort,
                null, null, null, null, null, null, Map.of());
    }
}
