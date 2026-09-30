package com.lily.cicd.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logging 모듈이 아직 없을 때 쓰는 구현. */
public final class Slf4jDeployLog implements DeployLog {

    private static final Logger log = LoggerFactory.getLogger(Slf4jDeployLog.class);

    @Override
    public void record(DeployContext context, String stage, String detail) {
        if (DeployStages.FAILED.equals(stage)) {
            log.error("deploy stage={} app={} detail={}", stage, context.appName(), detail);
            return;
        }
        log.info("deploy stage={} app={} color={} detail={}",
                stage, context.appName(), context.targetColor(), detail);
    }
}
