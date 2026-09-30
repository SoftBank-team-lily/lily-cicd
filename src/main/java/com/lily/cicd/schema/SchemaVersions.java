package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;

/** 어노테이션과 응답에 쓰는 스키마 버전 문자열. 빈 스키마는 {@code none} */
public final class SchemaVersions {

    public static final String NONE = "none";

    private SchemaVersions() {
    }

    public static String format(MigrationVersion version) {
        if (version == null) {
            return null;
        }
        return MigrationVersion.EMPTY.equals(version) ? NONE : version.getVersion();
    }

    public static MigrationVersion parse(String value) {
        return value == null || NONE.equals(value) ? MigrationVersion.EMPTY : MigrationVersion.fromVersion(value);
    }
}
