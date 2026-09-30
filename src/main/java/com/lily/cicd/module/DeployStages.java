package com.lily.cicd.module;

/**
 * {@link DeployLog#record} 의 stage 값.
 * Logging 모듈은 이 문자열로 배포 단계를 구분한다.
 */
public final class DeployStages {

    public static final String STARTED = "started";
    public static final String DATABASE = "database";
    public static final String COLOR = "color";
    /** 스키마 마이그레이션. 롤백에서는 스키마 되돌리기 */
    public static final String MIGRATION = "migration";
    public static final String ROLLBACK = "rollback";
    public static final String DEPLOYMENT = "deployment";
    public static final String READY = "ready";
    /** 판정 동안 사용자 트래픽 일부를 새 버전으로 보냄 */
    public static final String CANARY_TRAFFIC = "canary-traffic";
    /** 새 버전 판정 (통과·실패·건너뜀) */
    public static final String CANARY_ANALYSIS = "canary-analysis";
    public static final String SERVICE = "service";
    public static final String ROUTER = "router";
    public static final String MONITOR = "monitor";
    public static final String SCALE_DOWN = "scale-down";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    private DeployStages() {
    }
}
