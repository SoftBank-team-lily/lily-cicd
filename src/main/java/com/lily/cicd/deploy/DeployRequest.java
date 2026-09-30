package com.lily.cicd.deploy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * @param database   DB 가 필요한 앱만 넣는다. {@code postgres} 또는 {@code mysql}. 생략하면 DB 를 만들지 않는다
 * @param host       Ingress 호스트를 통째로 지정한다. 생략하면 {@code {appName}.{domain}}.
 *                   클라우드 버스팅에서 온프레미스 공개 주소({@code {appName}.{존}})로 들어온 요청을 그대로 받을 때 쓴다
 * @param migrations 마이그레이션 파일 (파일명 → SQL). database 와 함께 보낸다. docs/schema-migration.md
 * @param canaryPath canary 판정 때 새 버전과 이전 버전에 보낼 경로. 생략하면 readiness 경로. docs/canary-analysis.md
 */
public record DeployRequest(
        String appName,
        @NotBlank String imageUrl,
        @NotNull @Min(1) @Max(65535) Integer targetPort,
        String namespace,
        String domain,
        String readinessPath,
        String livenessPath,
        String appVersion,
        String imagePullSecret,
        Map<String, String> extraEnv,
        @Pattern(regexp = "postgres|mysql") String database,
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$") String host,
        Map<String, String> migrations,
        @Pattern(regexp = "^/[!-~]*$") String canaryPath
) {
    /** canary 경로 기본값으로 배포 */
    public DeployRequest(
            String appName, String imageUrl, Integer targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv, String database, String host, Map<String, String> migrations) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, database, host, migrations, null);
    }

    /** 호스트 지정 없이 배포 */
    public DeployRequest(
            String appName, String imageUrl, Integer targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv, String database) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, database, null, null, null);
    }

    /** DB 없이 배포 */
    public DeployRequest(
            String appName, String imageUrl, Integer targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, null, null, null, null);
    }

    public DeployCommand toCommand() {
        return new DeployCommand(
                appName,
                imageUrl,
                targetPort,
                namespace,
                domain,
                readinessPath,
                livenessPath,
                appVersion,
                imagePullSecret,
                extraEnv == null ? Map.of() : extraEnv,
                database,
                host,
                migrations == null ? Map.of() : migrations,
                canaryPath);
    }
}
