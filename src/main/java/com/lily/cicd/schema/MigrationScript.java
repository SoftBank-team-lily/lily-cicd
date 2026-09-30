package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;

import java.util.regex.Pattern;

/**
 * 마이그레이션 파일 하나.
 *
 * @param fileName 예: {@code V3__add_view_count.sql}
 * @param kind     V(적용) 또는 U(되돌리기)
 * @param version  Flyway 버전. {@code V1_1__x.sql} 은 {@code 1.1}
 */
public record MigrationScript(String fileName, Kind kind, MigrationVersion version, String sql) {

    /** V 파일 안에 이 주석이 한 줄로 있으면 되돌릴 수 없는 변경 (contract 단계의 DROP) */
    public static final String IRREVERSIBLE_MARKER = "-- lily:irreversible";

    private static final Pattern IRREVERSIBLE = Pattern.compile("(?im)^\\s*--\\s*lily:irreversible\\s*$");

    public enum Kind { VERSIONED, UNDO }

    public boolean irreversible() {
        return kind == Kind.VERSIONED && IRREVERSIBLE.matcher(sql).find();
    }
}
