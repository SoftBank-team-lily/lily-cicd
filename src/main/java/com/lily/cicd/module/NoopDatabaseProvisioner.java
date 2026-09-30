package com.lily.cicd.module;

import java.util.Map;

/** DB 모듈이 아직 없을 때 쓰는 구현. 환경변수를 추가하지 않는다. */
public final class NoopDatabaseProvisioner implements DatabaseProvisioner {

    @Override
    public Map<String, String> prepare(DeployContext context) {
        return Map.of();
    }
}
