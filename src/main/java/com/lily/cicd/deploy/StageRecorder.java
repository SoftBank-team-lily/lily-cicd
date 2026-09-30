package com.lily.cicd.deploy;

import com.lily.cicd.module.DeployContext;
import com.lily.cicd.module.DeployLog;
import com.lily.cicd.module.DeployMonitor;
import com.lily.cicd.module.DeployStages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 단계 기록을 응답 로그와 Logging 모듈에 같이 남긴다. 배포와 롤백이 같이 쓴다.
 * Logging, Monitoring 모듈의 예외는 배포를 멈추지 않는다.
 */
class StageRecorder {

    private static final Logger log = LoggerFactory.getLogger(StageRecorder.class);

    private final DeployLog deployLog;
    private final DeployMonitor deployMonitor;
    private final DeployProgress progress;

    StageRecorder(DeployLog deployLog, DeployMonitor deployMonitor) {
        this(deployLog, deployMonitor, null);
    }

    /** @param progress null 이면 진행 상황을 남기지 않는다 */
    StageRecorder(DeployLog deployLog, DeployMonitor deployMonitor, DeployProgress progress) {
        this.deployLog = deployLog;
        this.deployMonitor = deployMonitor;
        this.progress = progress;
    }

    void record(DeployContext context, List<String> logs, String stage, String detail) {
        if (logs.isEmpty() || !detail.equals(logs.get(logs.size() - 1))) {
            logs.add(detail);
        }
        if (progress != null) {
            progress.update(context.namespace(), context.appName(), stage, detail);
        }
        try {
            deployLog.record(context, stage, detail);
        } catch (RuntimeException e) {
            log.warn("logging module failed. stage={} message={}", stage, e.getMessage(), e);
        }
    }

    void failed(DeployContext context, List<String> logs, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (progress != null) {
            progress.update(context.namespace(), context.appName(), DeployStages.FAILED, message);
        }
        try {
            deployLog.record(context, DeployStages.FAILED, message);
        } catch (RuntimeException logError) {
            log.warn("logging module failed while reporting failure. message={}", logError.getMessage(), logError);
        }
        try {
            deployMonitor.failed(context, message);
        } catch (RuntimeException monitorError) {
            log.warn("monitoring module failed while reporting failure. message={}",
                    monitorError.getMessage(), monitorError);
        }
        logs.add("failed: " + message);
    }

    static String last(List<String> logs) {
        return logs.get(logs.size() - 1);
    }
}
