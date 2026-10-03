package com.lily.cicd.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgrollLinterTest {

    @Test
    void rename_타입_변경_nullable_컬럼_추가처럼_버전_스키마로_처리되는_연산은_통과한다() {
        List<String> violations = PgrollLinter.lint(List.of(yaml("02_ok", """
                operations:
                  - add_column:
                      table: posts
                      column: { name: slug, type: text, nullable: true }
                  - add_column:
                      table: posts
                      up: "0"
                      column: { name: views, type: int, default: "0" }
                  - alter_column:
                      table: posts
                      column: title
                      type: text
                      up: "upper(title)"
                      down: "lower(title)"
                  - rename_column: { table: posts, from: title, to: subject }
                  - drop_column: { table: posts, column: legacy }
                """)));

        assertEquals(List.of(), violations);
    }

    @Test
    void sql_연산은_거절한다() {
        List<String> violations = PgrollLinter.lint(List.of(yaml("03_raw", """
                operations:
                  - sql:
                      up: "ALTER TABLE posts ADD COLUMN x int"
                """)));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).startsWith("03_raw.yaml operations[0] sql: sql 연산은"), violations.get(0));
    }

    @Test
    void default_없는_NOT_NULL_add_column은_nullable을_안_써도_거절한다() {
        List<String> violations = PgrollLinter.lint(List.of(yaml("04_slug", """
                operations:
                  - add_column:
                      table: posts
                      up: "lower(title)"
                      column: { name: slug, type: text }
                  - add_column:
                      table: posts
                      up: "lower(title)"
                      column: { name: code, type: text, nullable: false }
                """)));

        assertEquals(2, violations.size());
        assertTrue(violations.get(0).contains("add_column slug: NOT NULL 컬럼은 default 가"), violations.get(0));
        assertTrue(violations.get(1).contains("add_column code"), violations.get(1));
    }

    @Test
    void down_없는_alter_column_데이터_변환은_거절하고_이름만_바꾸는_alter는_통과한다() {
        List<String> violations = PgrollLinter.lint(List.of(json("05_type", """
                {"operations": [
                  {"alter_column": {"table": "posts", "column": "views", "type": "bigint", "up": "views"}},
                  {"alter_column": {"table": "posts", "column": "title", "name": "subject"}}
                ]}""")));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("05_type.json operations[0] alter_column views: up 이나 type"),
                violations.get(0));
    }

    @Test
    void 읽을_수_없거나_operations가_없는_파일은_거절한다() {
        List<String> violations = PgrollLinter.lint(List.of(yaml("06_broken", "operations: [ {"),
                yaml("07_empty", "name: nothing")));

        assertEquals(2, violations.size());
        assertTrue(violations.get(0).startsWith("06_broken.yaml: 읽을 수 없다"), violations.get(0));
        assertTrue(violations.get(1).startsWith("07_empty.yaml: operations 목록이 없다"), violations.get(1));
    }

    private static PgrollSet.Migration yaml(String name, String body) {
        return new PgrollSet.Migration(Long.parseLong(name.substring(0, 2)), name, name + ".yaml", body);
    }

    private static PgrollSet.Migration json(String name, String body) {
        return new PgrollSet.Migration(Long.parseLong(name.substring(0, 2)), name, name + ".json", body);
    }
}
