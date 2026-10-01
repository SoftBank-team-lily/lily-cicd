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
    /** 배포 결과 주소의 scheme. Cloudflare 처럼 앞단이 TLS 를 끝내면 https */
    private String urlScheme = "http";
    private String readinessPath = "/actuator/health/readiness";
    private String livenessPath = "/actuator/health/liveness";
    private long readinessTimeoutSeconds = 120;
    /**
     * 블루그린 슬롯의 replica. 2 이상이면 Pod 하나가 죽거나 노드에서 빠져도 남은 Pod 가 받는다.
     * 2 이상일 때 PodDisruptionBudget(minAvailable 1) 을 같이 둔다.
     */
    private int replicas = 2;
    /**
     * selector 를 바꾼 뒤 이전 슬롯을 내리기 전에 기다리는 시간(초).
     * 같은 시간만큼 Pod preStop 에서 sleep 해서, nginx 가 엔드포인트를 갱신하기 전에 프로세스가 죽지 않게 한다.
     */
    private int drainSeconds = 5;
    /** {@code blue-green} 또는 {@code canary}. */
    private String strategy = "blue-green";
    /** canary 가 받기 시작하는 비율. 이후 100%까지 올린다. 1 이상 50 이하. */
    private int canaryWeightPercent = 20;

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getUrlScheme() { return urlScheme; }
    public void setUrlScheme(String urlScheme) { this.urlScheme = urlScheme; }

    public String getReadinessPath() { return readinessPath; }
    public void setReadinessPath(String readinessPath) { this.readinessPath = readinessPath; }

    public String getLivenessPath() { return livenessPath; }
    public void setLivenessPath(String livenessPath) { this.livenessPath = livenessPath; }

    public long getReadinessTimeoutSeconds() { return readinessTimeoutSeconds; }
    public void setReadinessTimeoutSeconds(long readinessTimeoutSeconds) {
        this.readinessTimeoutSeconds = readinessTimeoutSeconds;
    }

    public int getReplicas() { return replicas; }
    public void setReplicas(int replicas) { this.replicas = replicas; }

    public int getDrainSeconds() { return drainSeconds; }
    public void setDrainSeconds(int drainSeconds) { this.drainSeconds = drainSeconds; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public int getCanaryWeightPercent() { return canaryWeightPercent; }
    public void setCanaryWeightPercent(int canaryWeightPercent) {
        this.canaryWeightPercent = canaryWeightPercent;
    }

    /** 블루그린에서 전환 전에 새 버전을 일부 트래픽으로 판정한다. docs/canary-analysis.md */
    private CanaryAnalysis canaryAnalysis = new CanaryAnalysis();

    public CanaryAnalysis getCanaryAnalysis() { return canaryAnalysis; }
    public void setCanaryAnalysis(CanaryAnalysis canaryAnalysis) { this.canaryAnalysis = canaryAnalysis; }

    public static class CanaryAnalysis {
        private boolean enabled = true;
        /** 판정 동안 새 버전이 받는 사용자 트래픽 비율 (Ingress canary-weight). 1 이상 50 이하 */
        private int weightPercent = 10;
        /** 판정 시간 */
        private int durationSeconds = 30;
        /** 판정 요청 간격. 새 버전과 이전 버전에 같은 요청을 한 번씩 보낸다 */
        private int intervalMillis = 250;
        private int requestTimeoutMillis = 2000;
        /** 새 버전 에러율(5xx·연결 실패)이 이보다 크면 실패 */
        private double maxErrorRate = 0.05;
        /** 새 버전 p95 가 이보다 크면 실패 */
        private long maxP95Millis = 1000;
        /** 새 버전 p95 가 이전 버전의 이 배수보다 크고, 차이가 minP95RegressionMillis 이상이면 실패 */
        private double maxP95Ratio = 2.0;
        private long minP95RegressionMillis = 100;
        /** 판정에 필요한 최소 응답 수 */
        private int minSamples = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getWeightPercent() { return weightPercent; }
        public void setWeightPercent(int weightPercent) { this.weightPercent = weightPercent; }
        public int getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
        public int getIntervalMillis() { return intervalMillis; }
        public void setIntervalMillis(int intervalMillis) { this.intervalMillis = intervalMillis; }
        public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
        public void setRequestTimeoutMillis(int requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
        public double getMaxErrorRate() { return maxErrorRate; }
        public void setMaxErrorRate(double maxErrorRate) { this.maxErrorRate = maxErrorRate; }
        public long getMaxP95Millis() { return maxP95Millis; }
        public void setMaxP95Millis(long maxP95Millis) { this.maxP95Millis = maxP95Millis; }
        public double getMaxP95Ratio() { return maxP95Ratio; }
        public void setMaxP95Ratio(double maxP95Ratio) { this.maxP95Ratio = maxP95Ratio; }
        public long getMinP95RegressionMillis() { return minP95RegressionMillis; }
        public void setMinP95RegressionMillis(long minP95RegressionMillis) {
            this.minP95RegressionMillis = minP95RegressionMillis;
        }
        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
    }
}
