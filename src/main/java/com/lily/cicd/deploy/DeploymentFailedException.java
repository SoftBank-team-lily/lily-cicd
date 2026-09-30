package com.lily.cicd.deploy;

import java.util.List;

/**
 * 배포가 클러스터를 안정된 상태로 두고 중단됐을 때 던진다.
 * readiness 실패는 새 Deployment 를 지우고 Service 는 건드리지 않는다.
 */
public class DeploymentFailedException extends RuntimeException {

    private final List<String> logs;

    public DeploymentFailedException(String message, List<String> logs, Throwable cause) {
        super(message, cause);
        this.logs = List.copyOf(logs == null ? List.of() : logs);
    }

    public List<String> getLogs() {
        return logs;
    }
}
