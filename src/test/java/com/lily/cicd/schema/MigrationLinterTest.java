package com.lily.cicd.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationLinterTest {

    private static List<String> lint(String forwardSql) {
        MigrationSet set = MigrationSet.parse(Map.of("V2__change.sql", forwardSql, "U2__change.sql", "select 1;"));
        return MigrationLinter.lint(set.pending(Set.of()), set);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alter table posts add column view_count int not null default 0;",
            "alter table posts add column subtitle varchar(100);",
            "create table tags(id bigserial primary key, name text not null);",
            "create index idx_posts_title on posts(title);",
            "alter table posts alter column title drop not null;",
            "alter table posts add constraint chk check (title is not null);",
            "alter table posts drop constraint chk;",
            "insert into posts(title) values ('drop table posts; rename');",
            "update posts set subtitle = title; -- drop column title 은 다음 배포"
    })
    void 이전_슬롯과_호환되는_변경은_통과한다(String sql) {
        assertEquals(List.of(), lint(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alter table posts rename column title to headline;",
            "alter table posts rename to articles;",
            "alter table posts alter column title type text;",
            "alter table posts alter column title set data type text;",
            "alter table posts modify column title text;",
            "alter table posts change title headline text;",
            "alter table posts alter column title set not null;",
            "alter table posts add column author text not null;",
            "alter table posts drop column title;",
            "alter table posts drop title;",
            "drop table posts;",
            "truncate posts;"
    })
    void 이전_슬롯을_깨거나_데이터가_사라지는_변경은_거절한다(String sql) {
        List<String> violations = lint(sql);
        assertEquals(1, violations.size(), violations::toString);
        assertTrue(violations.get(0).startsWith("V2__change.sql: "));
    }

    @Test
    void irreversible_표시가_있으면_DROP을_허용하고_U를_요구하지_않는다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V3__drop_title.sql", "-- lily:irreversible\nalter table posts drop column title;"));

        assertEquals(List.of(), MigrationLinter.lint(set.pending(Set.of()), set));
    }

    @Test
    void irreversible_이어도_이름_변경은_막는다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V3__rename.sql", "-- lily:irreversible\nalter table posts rename column a to b;"));

        assertEquals(1, MigrationLinter.lint(set.pending(Set.of()), set).size());
    }

    @Test
    void U가_없으면_거절한다() {
        MigrationSet set = MigrationSet.parse(Map.of("V2__col.sql", "alter table posts add column c int;"));

        List<String> violations = MigrationLinter.lint(set.pending(Set.of()), set);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("U2__*.sql 이 없다"));
    }

    @Test
    void 첫_릴리스는_U_가_없어도_된다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V1__init.sql", "create table posts(id bigserial primary key, title text not null);",
                "V2__seed.sql", "insert into posts(title) values ('hello');"));

        assertEquals(List.of(), MigrationLinter.lint(set.pending(Set.of()), set, true));
    }

    @Test
    void 첫_릴리스여도_U_외의_규칙은_그대로다() {
        MigrationSet set = MigrationSet.parse(Map.of("V1__init.sql", "alter table posts rename column a to b;"));

        List<String> violations = MigrationLinter.lint(set.pending(Set.of()), set, true);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("이름 변경"));
    }

    @Test
    void 주석과_문자열을_지우고_문장별로_나눈다() {
        assertEquals(List.of("CREATE TABLE A(ID INT)", "INSERT INTO A VALUES ('')"),
                MigrationLinter.statements("/* drop table a */ create table a(id int); -- rename\n"
                        + "insert into a values ('it''s; drop');"));
    }
}
