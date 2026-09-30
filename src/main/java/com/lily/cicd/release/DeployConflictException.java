package com.lily.cicd.release;

import java.util.List;

/**
 * 지금 상태에서는 요청을 받을 수 없다 (409). 클러스터와 DB 는 바꾸지 않았다.
 * 예: 같은 앱의 다른 작업이 진행 중, 되돌릴 이전 릴리스가 없음, 되돌릴 수 없는 스키마.
 */
public class DeployConflictException extends RuntimeException {

    private final List<String> logs;

    public DeployConflictException(String message) {
        this(message, List.of());
    }

    public DeployConflictException(String message, List<String> logs) {
        super(message);
        this.logs = List.copyOf(logs);
    }

    public List<String> getLogs() {
        return logs;
    }
}
