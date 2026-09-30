package com.lily.cicd.module;

/**
 * 배포 한 건을 다른 모듈에 넘길 때 쓰는 값.
 * DB, Router, Logging, Monitoring 은 이 객체만 보면 된다.
 *
 * @param targetColor  색을 정하기 전에는 null. 정해진 뒤에는 blue 또는 green
 * @param serviceName  CICD 가 만든 Service. Router 는 이 이름으로 붙인다
 * @param servicePort  Service 가 여는 포트. 기본 80
 * @param metricsPath  앱 메트릭. lily-blog-sample 은 /actuator/prometheus
 * @param database     DB 엔진 (postgres / mysql). null 이면 DB 가 필요 없는 앱
 */
public record DeployContext(
        String appName,
        String namespace,
        String imageUrl,
        int targetPort,
        int servicePort,
        String host,
        String appVersion,
        String targetColor,
        String serviceName,
        String metricsPath,
        String database
) {
    /** DB 가 필요 없는 앱 */
    public DeployContext(
            String appName, String namespace, String imageUrl, int targetPort, int servicePort,
            String host, String appVersion, String targetColor, String serviceName, String metricsPath) {
        this(appName, namespace, imageUrl, targetPort, servicePort,
                host, appVersion, targetColor, serviceName, metricsPath, null);
    }

    public DeployContext withTargetColor(String color) {
        return new DeployContext(
                appName, namespace, imageUrl, targetPort, servicePort,
                host, appVersion, color, serviceName, metricsPath, database);
    }
}
