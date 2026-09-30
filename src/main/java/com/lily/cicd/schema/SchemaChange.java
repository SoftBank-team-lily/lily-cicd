package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;

import java.util.List;

/**
 * 배포 한 건이 스키마를 어디서 어디로 옮겼는지.
 *
 * @param applied 이번에 적용한 버전. 배포가 전환 전에 실패하면 이 버전들을 되돌린다
 */
public record SchemaChange(MigrationVersion from, MigrationVersion to, List<MigrationVersion> applied) {

    /** 마이그레이션을 보내지 않은 배포 (앱이 스키마를 직접 관리) */
    public static final SchemaChange NONE = new SchemaChange(null, null, List.of());

    public boolean managed() {
        return to != null;
    }
}
