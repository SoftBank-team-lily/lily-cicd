package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL 에서 마이그레이션, dry-run, 되돌리기, 백업을 확인한다.
 * lily-blog-sample 의 posts 테이블을 흉내 낸다.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigratorPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String V1 = "create table posts(id bigserial primary key, title text not null);";
    private static final String U1 = "drop table posts;";
    private static final String V2 = "alter table posts add column view_count int not null default 0;";
    private static final String U2 = "alter table posts drop column view_count;";

    private final SchemaMigrator migrator = new SchemaMigrator();
    private List<String> logs;

    @BeforeEach
    void resetDatabase() throws SQLException {
        logs = new ArrayList<>();
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute("drop schema public cascade; create schema public;");
            try (ResultSet rs = st.executeQuery("select nspname from pg_namespace where nspname like 'lily_bak_%'")) {
                List<String> backups = new ArrayList<>();
                while (rs.next()) {
                    backups.add(rs.getString(1));
                }
                for (String backup : backups) {
                    st.execute("drop schema \"" + backup + "\" cascade");
                }
            }
        }
    }

    @Test
    void 적용하지_않은_V만_적용하고_다시_부르면_아무것도_하지_않는다() throws SQLException {
        SchemaChange first = migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);

        assertEquals(MigrationVersion.EMPTY, first.from());
        assertEquals(v("1"), first.to());
        assertEquals(List.of(v("1")), first.applied());
        assertTrue(logs.contains("schema: dry-run ok (V → U → V, rolled back)"));

        SchemaChange second = migrator.migrate(env(), set(Map.of(
                "V1__posts.sql", V1, "U1__posts.sql", U1, "V2__views.sql", V2, "U2__views.sql", U2)), logs);

        assertEquals(v("1"), second.from());
        assertEquals(v("2"), second.to());
        assertEquals(List.of(v("2")), second.applied());
        assertTrue(columnExists("view_count"));

        SchemaChange again = migrator.migrate(env(), set(Map.of(
                "V1__posts.sql", V1, "U1__posts.sql", U1, "V2__views.sql", V2, "U2__views.sql", U2)), logs);
        assertEquals(List.of(), again.applied());
        assertEquals("schema: up to date at 2", logs.get(logs.size() - 1));
    }

    @Test
    void 앱이_자체_Flyway로_적용한_이력을_그대로_이어받는다() throws SQLException {
        // lily-blog-sample 처럼 앱이 먼저 V1 을 적용해 둔 DB
        migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);

        SchemaChange change = migrator.migrate(env(), set(Map.of(
                "V1__posts.sql", V1, "V2__views.sql", V2, "U2__views.sql", U2)), logs);

        // V1 에는 U 가 없어도 이미 적용된 버전이라 lint 대상이 아니다
        assertEquals(List.of(v("2")), change.applied());
    }

    @Test
    void lint를_통과하지_못하면_DB를_건드리지_않는다() throws SQLException {
        migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);

        MigrationSet bad = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__rename.sql", "alter table posts rename column title to headline;",
                "U2__rename.sql", "alter table posts rename column headline to title;"));

        assertThrows(IllegalArgumentException.class, () -> migrator.migrate(env(), bad, logs));
        assertTrue(columnExists("title"));
        assertEquals(1, historyCount());
    }

    @Test
    void U가_V를_되돌리지_못하면_dry_run에서_막는다() throws SQLException {
        migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);
        MigrationSet brokenUndo = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", "select 1;"));

        SchemaOperationException e = assertThrows(SchemaOperationException.class,
                () -> migrator.migrate(env(), brokenUndo, logs));

        assertTrue(e.getMessage().contains("V2__views.sql"), e.getMessage());
        assertFalse(columnExists("view_count"));
        assertEquals(1, historyCount());
    }

    @Test
    void 여러_V_중_하나라도_실패하면_하나도_적용하지_않는다() throws SQLException {
        // dry-run 을 거치지 않고 Flyway 적용만 본다 (group: 대상 전체가 한 트랜잭션)
        migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);
        execute("insert into posts(title) values ('a'), ('a')");
        MigrationSet scripts = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2,
                "V3__unique.sql", "create unique index ux_title on posts(title);",
                "U3__unique.sql", "drop index ux_title;"));

        SchemaDatabase db = new SchemaDatabase(DbTarget.from(env()));
        assertThrows(SchemaOperationException.class, () -> db.migrate(scripts));
        assertFalse(columnExists("view_count"));
        assertEquals(1, historyCount());
    }

    @Test
    void 롤백은_U를_역순으로_실행하고_행은_남기고_이력을_지운다() throws SQLException {
        MigrationSet scripts = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2,
                "V3__subtitle.sql", "alter table posts add column subtitle text;",
                "U3__subtitle.sql", "alter table posts drop column subtitle;"));
        migrator.migrate(env(), scripts, logs);
        execute("insert into posts(title, view_count, subtitle) values ('hello', 7, 'sub')");

        migrator.rollback(env(), scripts, v("1"), true, logs);

        assertFalse(columnExists("view_count"));
        assertFalse(columnExists("subtitle"));
        assertEquals(1, count("select count(*) from posts where title = 'hello'"));
        assertEquals(1, historyCount());
        assertTrue(logs.get(logs.size() - 1).contains("[U3__subtitle.sql, U2__views.sql]"));
        // 되돌리기 전 값은 백업 스키마에 남는다
        String backup = logs.stream().filter(l -> l.startsWith("schema: backup ")).findFirst().orElseThrow()
                .substring("schema: backup ".length());
        assertEquals(7, count("select view_count from \"" + backup + "\".posts"));
    }

    @Test
    void 되돌린_버전은_고친_내용으로_다시_배포할_수_있다() throws SQLException {
        MigrationSet scripts = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2));
        migrator.migrate(env(), scripts, logs);
        migrator.rollback(env(), scripts, v("1"), false, logs);

        MigrationSet fixed = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", "alter table posts add column view_count bigint not null default 0;",
                "U2__views.sql", U2));
        SchemaChange change = migrator.migrate(env(), fixed, logs);

        assertEquals(List.of(v("2")), change.applied());
        assertTrue(columnExists("view_count"));
    }

    @Test
    void irreversible_버전을_지나서는_되돌리지_않는다() throws SQLException {
        MigrationSet scripts = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2,
                "V3__drop.sql", "-- lily:irreversible\nalter table posts drop column view_count;"));
        migrator.migrate(env(), scripts, logs);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> migrator.rollback(env(), scripts, v("1"), true, logs));

        assertTrue(e.getMessage().contains("lily:irreversible"));
        assertEquals(3, historyCount());
        assertEquals(List.of(v("3"), v("2")), migrator.versionsAbove(env(), v("1")));
    }

    @Test
    void U가_실패하면_전부_취소한다() throws SQLException {
        MigrationSet scripts = set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2,
                "V3__subtitle.sql", "alter table posts add column subtitle text;",
                "U3__subtitle.sql", "alter table posts drop column subtitle;"));
        migrator.migrate(env(), scripts, logs);
        // dry-run 뒤에 누가 컬럼을 먼저 지운 상황
        execute("alter table posts drop column view_count");

        assertThrows(SchemaOperationException.class, () -> migrator.rollback(env(), scripts, v("1"), false, logs));

        assertTrue(columnExists("subtitle"));
        assertEquals(3, historyCount());
    }

    @Test
    void 백업은_최근_3개만_남긴다() throws SQLException {
        migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);
        SchemaDatabase db = new SchemaDatabase(DbTarget.from(env()));

        for (int i = 0; i < 5; i++) {
            db.backup();
        }

        assertEquals(SchemaDatabase.BACKUPS_TO_KEEP,
                count("select count(*) from pg_namespace where nspname like 'lily_bak_%'"));
    }

    @Test
    void 첫_릴리스는_U_없이_적용하고_다음_릴리스의_새_V_부터_U_를_요구한다() throws SQLException {
        SchemaChange first = migrator.migrate(env(), set(Map.of("V1__posts.sql", V1)), logs);

        assertEquals(List.of(v("1")), first.applied());
        assertTrue(logs.contains("schema: dry-run ok (V → U → V, rolled back)"));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("schema: first release, U 없이 적용 [V1__posts.sql]")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                migrator.migrate(env(), set(Map.of("V1__posts.sql", V1, "V2__views.sql", V2)), logs));
        assertTrue(e.getMessage().contains("U2__*.sql 이 없다"));
        assertFalse(columnExists("view_count"));

        SchemaChange second = migrator.migrate(env(), set(Map.of(
                "V1__posts.sql", V1, "V2__views.sql", V2, "U2__views.sql", U2)), logs);
        assertEquals(List.of(v("2")), second.applied());
        assertTrue(columnExists("view_count"));
    }

    private static MigrationSet set(Map<String, String> files) {
        return MigrationSet.parse(new HashMap<>(files));
    }

    private static MigrationVersion v(String version) {
        return MigrationVersion.fromVersion(version);
    }

    private static Map<String, String> env() {
        return Map.of("DB_URL", POSTGRES.getJdbcUrl(),
                "DB_USERNAME", POSTGRES.getUsername(),
                "DB_PASSWORD", POSTGRES.getPassword());
    }

    private static Connection connection() throws SQLException {
        return new SchemaDatabase(DbTarget.from(env())).connect();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection conn = connection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int historyCount() throws SQLException {
        return count("select count(*) from flyway_schema_history");
    }

    private static boolean columnExists(String column) throws SQLException {
        return count("select count(*) from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'posts' and column_name = '" + column + "'") == 1;
    }
}
