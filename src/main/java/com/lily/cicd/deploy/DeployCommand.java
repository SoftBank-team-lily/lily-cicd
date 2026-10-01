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
 * @param host             Ingress 호스트 전체. null 이면 {@code {appName}.{domain}}
 * @param migrations       마이그레이션 파일 (파일명 → SQL). 비어 있으면 앱이 스키마를 직접 관리한다. docs/schema-migration.md
 * @param canaryPath       canary 판정 때 새 버전과 이전 버전에 보낼 경로. null 이면 readiness 경로. docs/canary-analysis.md
 * @param databaseEnv      DB 접속 환경변수를 호출자가 정해 보낸다 (온프레미스 DB 를 역방향 터널로 쓰는 클라우드 대기 배포).
 *                         비어 있지 않으면 DB 모듈을 부르지 않고 이 값을 슬롯 Secret 에 넣는다. database 와 같이 보내지 않는다
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
        String database,
        String host,
        Map<String, String> migrations,
        String canaryPath,
        Map<String, String> databaseEnv
) {
    /** DB 접속 정보는 DB 모듈이 정한다 */
    public DeployCommand(
            String appName, String imageUrl, int targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv, String database, String host, Map<String, String> migrations,
            String canaryPath) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, database, host, migrations, canaryPath, null);
    }

    /** canary 경로 기본값으로 배포 */
    public DeployCommand(
            String appName, String imageUrl, int targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv, String database, String host, Map<String, String> migrations) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, database, host, migrations, null);
    }

    /** 마이그레이션 없이 배포 */
    public DeployCommand(
            String appName, String imageUrl, int targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv, String database, String host) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, database, host, Map.of(), null);
    }

    /** 호스트 지정과 마이그레이션 없이 배포 */
    public DeployCommand(
            String appName, String imageUrl, int targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv, String database) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, database, null, Map.of(), null);
    }

    /** DB 없이 배포 */
    public DeployCommand(
            String appName, String imageUrl, int targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, null, null, Map.of(), null);
    }

    public static DeployCommand of(String appName, String imageUrl, int targetPort) {
        return new DeployCommand(
                appName, imageUrl, targetPort,
                null, null, null, null, null, null, Map.of());
    }
}
