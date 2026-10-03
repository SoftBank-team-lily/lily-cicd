package com.lily.cicd.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 pgroll CLI 를 PostgreSQL 에 돌린다. lily-blog-sample 의 posts 테이블을 흉내 낸다.
 * CLI 는 Gradle downloadPgroll 이 받는다 (linux). 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgrollMigratorPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String CREATE_POSTS = """
            operations:
              - create_table:
                  name: posts
                  columns:
                    - { name: id, type: bigserial, pk: true }
                    - { name: title, type: varchar(200), nullable: false }
            """;
    private static final String ADD_SLUG = """
            operations:
              - add_column:
                  table: posts
                  up: "lower(replace(title, ' ', '-'))"
                  column: { name: slug, type: varchar(220), nullable: true }
            """;
    private static final String TITLE_TO_SUBJECT = """
            operations:
              - alter_column:
                  table: posts
                  column: title
                  type: text
                  up: "upper(title)"
                  down: "lower(subject)"
              - rename_column:
                  table: posts
                  from: title
                  to: subject
            """;

    private PgrollCli cli;
    private PgrollMigrator migrator;
    private List<String> logs;

    @BeforeEach
    void resetDatabase() throws SQLException {
        String binary = System.getProperty("pgroll.binary", "");
        assumeTrue(!binary.isEmpty() && Files.isExecutable(Path.of(binary)), "pgroll CLI 없음");
        cli = new PgrollCli(new PgrollCli.Settings(binary, "disable", 500, 60, 1000, "0s"));
        migrator = new PgrollMigrator(cli);
        logs = new ArrayList<>();
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("select nspname from pg_namespace where nspname like 'public_%'")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
            for (String schema : schemas) {
                st.execute("drop schema \"" + schema + "\" cascade");
            }
            st.execute("drop schema if exists pgroll cascade; drop event trigger if exists pg_roll_handle_ddl;"
                    + " drop event trigger if exists pg_roll_handle_drop; drop schema public cascade; create schema public;");
        }
    }

    @Test
    void 처음_배포는_마이그레이션을_모두_적용하고_마지막_것을_active로_남긴다() throws SQLException {
        init();

        PgrollMigrator.PgrollChange change = migrator.migrate(env(), set("01_create_posts", CREATE_POSTS,
                "02_add_slug", ADD_SLUG), false, logs);

        assertNull(change.from());
        assertEquals("02_add_slug", change.to());
        assertTrue(change.started());
        assertEquals(Optional.of("02_add_slug"), migrator.active(env()));
        assertTrue(schemaExists("public_02_add_slug"));
    }

    @Test
    void 다음_마이그레이션을_시작하면_이전_active를_complete하고_시작한다() throws SQLException {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs);

        PgrollMigrator.PgrollChange change = migrator.migrate(env(), set("01_create_posts", CREATE_POSTS,
                "02_add_slug", ADD_SLUG), true, logs);

        assertEquals("01_create_posts", change.from());
        assertEquals("01_create_posts", change.completed());
        assertEquals(Optional.of("02_add_slug"), migrator.active(env()));
    }

    @Test
    void 새_마이그레이션이_없으면_active를_그대로_두고_최신_버전으로_접속한다() {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs);

        PgrollMigrator.PgrollChange change = migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), true, logs);

        assertFalse(change.started());
        assertEquals("01_create_posts", change.to());
        assertEquals(Optional.of("01_create_posts"), migrator.active(env()));
        assertEquals(Optional.of("01_create_posts"), migrator.latest(env()));
    }

    @Test
    void 이전_버전이_실행_중이면_대기_중인_마이그레이션_두개를_거절하고_DB를_바꾸지_않는다() {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs);

        assertThrows(IllegalArgumentException.class, () -> migrator.migrate(env(), set(
                "01_create_posts", CREATE_POSTS, "02_add_slug", ADD_SLUG, "03_title_to_subject", TITLE_TO_SUBJECT),
                true, logs));

        assertEquals(Optional.of("01_create_posts"), migrator.active(env()));
    }

    @Test
    void 전환_후_rollback해도_새_버전이_쓴_행과_값이_이전_버전에_남는다() throws SQLException {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs);
        migrator.complete(env(), "01_create_posts", logs);
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS, "03_title_to_subject", TITLE_TO_SUBJECT),
                true, logs);
        execute("public_01_create_posts", "insert into posts(title) values ('blue post')");
        execute("public_03_title_to_subject", "insert into posts(subject) values ('GREEN POST')");

        migrator.rollback(env(), "03_title_to_subject", logs);

        assertEquals(List.of("blue post", "green post"), titles("public_01_create_posts"));
        assertFalse(schemaExists("public_03_title_to_subject"));
        assertEquals(Optional.empty(), migrator.active(env()));
    }

    @Test
    void 이력_없이_테이블만_있는_DB는_baseline을_남기고_시작한다() throws SQLException {
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute("create table posts(id bigserial primary key, title varchar(200) not null)");
            st.execute("insert into posts(title) values ('flyway 시절 글')");
        }
        init();

        migrator.migrate(env(), set("02_add_slug", ADD_SLUG), true, logs);

        assertTrue(logs.stream().anyMatch(line -> line.contains("baseline " + PgrollMigrator.BASELINE)));
        assertEquals(List.of("flyway-시절-글"), column("public_02_add_slug", "slug"));
    }

    @Test
    void complete된_마이그레이션은_rollback과_complete를_거절한다() {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs);
        migrator.complete(env(), "01_create_posts", logs);

        assertThrows(IllegalStateException.class, () -> migrator.rollback(env(), "01_create_posts", logs));
        assertThrows(IllegalStateException.class, () -> migrator.complete(env(), "01_create_posts", logs));
    }

    @Test
    void DB의_최신_마이그레이션이_커밋에_없으면_거절한다() {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS, "02_add_slug", ADD_SLUG), false, logs);

        assertThrows(IllegalArgumentException.class,
                () -> migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), true, logs));
    }

    @Test
    void pgroll이_켜지지_않은_DB는_거절하고_최신_버전은_빈_값이다() {
        assertThrows(IllegalStateException.class,
                () -> migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs));
        assertEquals(Optional.empty(), migrator.latest(env()));
    }

    @Test
    void 규칙_위반_마이그레이션은_이전_active를_complete하지_않고_400으로_거절한다() {
        init();
        migrator.migrate(env(), set("01_create_posts", CREATE_POSTS), false, logs);
        String raw = "operations:\n  - sql:\n      up: \"ALTER TABLE posts ADD COLUMN x int\"\n";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> migrator.migrate(env(),
                set("01_create_posts", CREATE_POSTS, "02_raw", raw), true, logs));

        assertTrue(e.getMessage().contains("sql 연산은"), e.getMessage());
        assertEquals(Optional.of("01_create_posts"), migrator.active(env()));
    }

    @Test
    void CLI가_실패하면_마지막_출력_줄을_이유로_던진다() {
        init();
        String bad = "operations:\n  - add_column:\n      table: nope\n      column: { name: x, type: int, nullable: true }\n";

        SchemaOperationException e = assertThrows(SchemaOperationException.class,
                () -> migrator.migrate(env(), set("01_bad", bad), false, logs));

        assertTrue(e.getMessage().startsWith("pgroll start 실패: "), e.getMessage());
    }

    private void init() {
        // 운영에서는 lily-db-provisioner 가 관리자 계정으로 한다. 컨테이너 계정은 superuser 다
        cli.run(target(), List.of("init"));
    }

    private static PgrollSet set(String... nameAndBody) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < nameAndBody.length; i += 2) {
            files.put(nameAndBody[i] + ".yaml", nameAndBody[i + 1]);
        }
        return PgrollSet.parse(files);
    }

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("DB_URL", POSTGRES.getJdbcUrl());
        env.put("DB_USERNAME", POSTGRES.getUsername());
        env.put("DB_PASSWORD", POSTGRES.getPassword());
        return env;
    }

    private static DbTarget target() {
        return DbTarget.from(env());
    }

    private static Connection connection() throws SQLException {
        return new SchemaDatabase(target()).connect();
    }

    private static void execute(String schema, String sql) throws SQLException {
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute("set search_path to " + schema);
            st.execute(sql);
        }
    }

    private static List<String> titles(String schema) throws SQLException {
        return column(schema, "title");
    }

    private static List<String> column(String schema, String name) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute("set search_path to " + schema);
            try (ResultSet rs = st.executeQuery("select " + name + " from posts order by id")) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        return values;
    }

    private static boolean schemaExists(String schema) throws SQLException {
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select 1 from pg_namespace where nspname = '" + schema + "'")) {
            return rs.next();
        }
    }
}
