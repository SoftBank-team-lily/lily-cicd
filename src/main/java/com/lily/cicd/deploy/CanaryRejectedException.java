package com.lily.cicd.deploy;

import java.util.List;

/**
 * canary 판정에서 새 버전이 떨어졌다. 트래픽은 이전 버전에 그대로 있고, 새 Deployment 와 이번 스키마 변경은 되돌렸다.
 */
public class CanaryRejectedException extends DeploymentFailedException {

    public CanaryRejectedException(String message, List<String> logs) {
        super(message, logs, null);
    }
}
