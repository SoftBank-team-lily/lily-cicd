package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 은 DDL 이 자동 커밋이라 dry-run 과 백업이 없고, 되돌리기는 버전마다 반영된다.
 * 클러스터에는 MySQL RDS 가 없어서 이 테스트가 MySQL 경로의 유일한 검증이다.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigratorMysqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final String V1 = "create table posts(id bigint auto_increment primary key, title varchar(100) not null);";
    private static final String U1 = "drop table posts;";
    private static final String V2 = "alter table posts add column view_count int not null default 0;";
    private static final String U2 = "alter table posts drop column view_count;";
    private static final String V3 = "alter table posts add column subtitle varchar(100); create index ix_sub on posts(subtitle);";
    private static final String U3 = "drop index ix_sub on posts; alter table posts drop column subtitle;";

    private final SchemaMigrator migrator = new SchemaMigrator();
    private final List<String> logs = new ArrayList<>();

    @BeforeEach
    void resetDatabase() throws SQLException {
        execute("drop table if exists posts");
        execute("drop table if exists flyway_schema_history");
    }

    @Test
    void 적용하고_여러_문장짜리_U로_되돌린다() throws SQLException {
        MigrationSet scripts = MigrationSet.parse(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2, "V3__subtitle.sql", V3, "U3__subtitle.sql", U3));

        SchemaChange change = migrator.migrate(env(), scripts, logs);
        assertEquals(MigrationVersion.fromVersion("3"), change.to());
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("schema: dry-run skipped")));
        execute("insert into posts(title, view_count, subtitle) values ('hello', 3, 's')");

        migrator.rollback(env(), scripts, MigrationVersion.fromVersion("1"), true, logs);

        assertFalse(columnExists("subtitle"));
        assertFalse(columnExists("view_count"));
        assertEquals(1, count("select count(*) from posts"));
        assertEquals(1, count("select count(*) from flyway_schema_history"));
        assertTrue(logs.contains("schema: backup skipped (mysql)"));
    }

    @Test
    void U가_중간에_실패하면_이미_되돌린_버전을_알린다() throws SQLException {
        MigrationSet scripts = MigrationSet.parse(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2, "V3__subtitle.sql", V3, "U3__subtitle.sql", U3));
        migrator.migrate(env(), scripts, logs);
        execute("alter table posts drop column view_count");

        SchemaOperationException e = assertThrows(SchemaOperationException.class,
                () -> migrator.rollback(env(), scripts, MigrationVersion.fromVersion("1"), false, logs));

        assertTrue(e.getMessage().contains("이미 되돌린 버전: [v3]"), e.getMessage());
        assertFalse(columnExists("subtitle"));
        assertEquals(2, count("select count(*) from flyway_schema_history"));
    }

    @Test
    void migrate가_일부만_반영되면_반영된_버전을_되돌린다() throws SQLException {
        migrator.migrate(env(), MigrationSet.parse(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);
        MigrationSet scripts = MigrationSet.parse(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__views.sql", V2, "U2__views.sql", U2,
                "V3__broken.sql", "alter table missing_table add column y int;", "U3__broken.sql", "select 1;"));

        assertThrows(SchemaOperationException.class, () -> migrator.migrate(env(), scripts, logs));

        assertFalse(columnExists("view_count"));
        assertTrue(logs.contains("schema: reverted [2]"), logs::toString);
    }

    @Test
    void 한_파일_안에서_커밋된_DDL은_U로_되돌린다() throws SQLException {
        migrator.migrate(env(), MigrationSet.parse(Map.of("V1__posts.sql", V1, "U1__posts.sql", U1)), logs);
        MigrationSet scripts = MigrationSet.parse(Map.of(
                "V1__posts.sql", V1, "U1__posts.sql", U1,
                "V2__partial.sql", "create table extra(id int); alter table missing_table add column y int;",
                "U2__partial.sql", "drop table extra;"));

        assertThrows(SchemaOperationException.class, () -> migrator.migrate(env(), scripts, logs));

        assertEquals(0, count("select count(*) from information_schema.tables "
                + "where table_schema = database() and table_name = 'extra'"));
        assertTrue(logs.stream().anyMatch(line -> line.contains("reverted unrecorded")), logs::toString);
    }

    private static Map<String, String> env() {
        return Map.of("DB_URL", MYSQL.getJdbcUrl(), "DB_USERNAME", MYSQL.getUsername(),
                "DB_PASSWORD", MYSQL.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = new SchemaDatabase(DbTarget.from(env())).connect(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection conn = new SchemaDatabase(DbTarget.from(env())).connect();
             Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean columnExists(String column) throws SQLException {
        return count("select count(*) from information_schema.columns where table_schema = database() "
                + "and table_name = 'posts' and column_name = '" + column + "'") == 1;
    }
}
