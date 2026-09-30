package com.lily.cicd.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 클러스터에 공통으로 적용하는 배포 기본값.
 * 요청마다 바꿀 값은 {@code DeployCommand} 로 덮어쓴다.
 */
@ConfigurationProperties(prefix = "lily.deploy")
public class DeployProperties {

    private String appName = "lily";
    private String namespace = "default";
    private String domain = "domain.com";
    private String readinessPath = "/actuator/health/readiness";
    private String livenessPath = "/actuator/health/liveness";
    private long readinessTimeoutSeconds = 120;

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getReadinessPath() { return readinessPath; }
    public void setReadinessPath(String readinessPath) { this.readinessPath = readinessPath; }

    public String getLivenessPath() { return livenessPath; }
    public void setLivenessPath(String livenessPath) { this.livenessPath = livenessPath; }

    public long getReadinessTimeoutSeconds() { return readinessTimeoutSeconds; }
    public void setReadinessTimeoutSeconds(long readinessTimeoutSeconds) {
        this.readinessTimeoutSeconds = readinessTimeoutSeconds;
    }
}
