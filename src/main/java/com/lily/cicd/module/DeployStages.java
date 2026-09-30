package com.lily.cicd.module;

/**
 * {@link DeployLog#record} 의 stage 값.
 * Logging 모듈은 이 문자열로 배포 단계를 구분한다.
 */
public final class DeployStages {

    public static final String STARTED = "started";
    public static final String DATABASE = "database";
    public static final String COLOR = "color";
    public static final String DEPLOYMENT = "deployment";
    public static final String READY = "ready";
    public static final String SERVICE = "service";
    public static final String ROUTER = "router";
    public static final String MONITOR = "monitor";
    public static final String SCALE_DOWN = "scale-down";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    private DeployStages() {
    }
}
