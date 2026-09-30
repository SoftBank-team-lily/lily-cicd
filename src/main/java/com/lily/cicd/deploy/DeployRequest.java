package com.lily.cicd.deploy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

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
        Map<String, String> extraEnv
) {
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
                extraEnv == null ? Map.of() : extraEnv);
    }
}
