package com.lily.cicd.module;

/** Monitoring 모듈이 아직 없을 때 쓰는 구현. */
public final class NoopDeployMonitor implements DeployMonitor {

    @Override
    public void attached(DeployContext context) {
    }

    @Override
    public void failed(DeployContext context, String message) {
    }
}
