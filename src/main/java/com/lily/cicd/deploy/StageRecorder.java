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

    StageRecorder(DeployLog deployLog, DeployMonitor deployMonitor) {
        this.deployLog = deployLog;
        this.deployMonitor = deployMonitor;
    }

    void record(DeployContext context, List<String> logs, String stage, String detail) {
        if (logs.isEmpty() || !detail.equals(logs.get(logs.size() - 1))) {
            logs.add(detail);
        }
        try {
            deployLog.record(context, stage, detail);
        } catch (RuntimeException e) {
            log.warn("logging module failed. stage={} message={}", stage, e.getMessage(), e);
        }
    }

    void failed(DeployContext context, List<String> logs, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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
