package com.lily.cicd.module;

/**
 * Logging 모듈이 구현한다.
 * 배포가 실패해도 이 호출이 예외를 던지면 CICD 는 경고만 남기고 배포를 계속한다.
 * 구현 빈이 없으면 SLF4J 로 같은 내용을 찍는다.
 */
public interface DeployLog {

    void record(DeployContext context, String stage, String detail);
}
