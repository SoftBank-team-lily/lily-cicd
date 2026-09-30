package com.lily.cicd.deploy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * @param database DB 가 필요한 앱만 넣는다. {@code postgres} 또는 {@code mysql}. 생략하면 DB 를 만들지 않는다
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
        @Pattern(regexp = "postgres|mysql") String database
) {
    /** DB 없이 배포 */
    public DeployRequest(
            String appName, String imageUrl, Integer targetPort, String namespace, String domain,
            String readinessPath, String livenessPath, String appVersion, String imagePullSecret,
            Map<String, String> extraEnv) {
        this(appName, imageUrl, targetPort, namespace, domain, readinessPath, livenessPath,
                appVersion, imagePullSecret, extraEnv, null);
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
                database);
    }
}
