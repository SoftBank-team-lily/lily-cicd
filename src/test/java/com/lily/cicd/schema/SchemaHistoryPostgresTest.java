package com.lily.cicd.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 실제 PostgreSQL 에서 Flyway·pgroll 이력을 읽는다. pgroll CLI 는 Gradle downloadPgroll 이 받는다 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaHistoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private final SchemaHistory history = new SchemaHistory();
    private final DbTarget target = DbTarget.from(Map.of("DB_URL", POSTGRES.getJdbcUrl(),
            "DB_USERNAME", POSTGRES.getUsername(), "DB_PASSWORD", POSTGRES.getPassword()));

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection conn = new SchemaDatabase(target).connect(); Statement st = conn.createStatement()) {
            st.execute("drop schema if exists pgroll cascade; drop event trigger if exists pg_roll_handle_ddl;"
                    + " drop event trigger if exists pg_roll_handle_drop; drop schema public cascade; create schema public;");
            st.execute("do $$ declare s text; begin for s in select nspname from pg_namespace where nspname like 'public\\_%'"
                    + " loop execute format('drop schema %I cascade', s); end loop; end $$;");
        }
    }

    @Test
    void 이력이_없으면_엔진이_비어_있고_목록도_비어_있다() {
        SchemaHistory.History read = history.read(target);

        assertNull(read.engine());
        assertTrue(read.entries().isEmpty());
    }

    @Test
    void Flyway_로_적용한_버전을_순서대로_읽는다() {
        new SchemaMigrator().migrate(Map.of("DB_URL", POSTGRES.getJdbcUrl(), "DB_USERNAME", POSTGRES.getUsername(),
                        "DB_PASSWORD", POSTGRES.getPassword()),
                MigrationSet.parse(Map.of("V1__create_posts.sql", "create table posts(id bigserial primary key);",
                        "V2__add_title.sql", "alter table posts add column title text;")),
                new ArrayList<>());

        SchemaHistory.History read = history.read(target);

        assertEquals("flyway", read.engine());
        assertEquals(List.of("1", "2"), read.entries().stream().map(SchemaHistory.Entry::version).toList());
        assertEquals("add title", read.entries().get(1).description());
        assertEquals("applied", read.entries().get(1).state());
        assertNotNull(read.entries().get(1).startedAt());
    }

    @Test
    void pgroll_을_켠_DB_는_pgroll_이력을_complete_와_active_로_읽는다() {
        String binary = System.getProperty("pgroll.binary", "");
        assumeTrue(!binary.isEmpty() && Files.isExecutable(Path.of(binary)), "pgroll CLI 없음");
        PgrollCli cli = new PgrollCli(new PgrollCli.Settings(binary, "disable", 500, 60, 1000, "0s"));
        cli.run(target, List.of("init"));
        Map<String, String> env = Map.of("DB_URL", POSTGRES.getJdbcUrl(), "DB_USERNAME", POSTGRES.getUsername(),
                "DB_PASSWORD", POSTGRES.getPassword());
        new PgrollMigrator(cli).migrate(env, PgrollSet.parse(Map.of(
                "01_create_posts.yaml", "operations:\n  - create_table:\n      name: posts\n      columns:\n"
                        + "        - { name: id, type: bigserial, pk: true }\n",
                "02_add_title.yaml", "operations:\n  - add_column:\n      table: posts\n"
                        + "      column: { name: title, type: text, nullable: true }\n")), false, new ArrayList<>());

        SchemaHistory.History read = history.read(target);

        assertEquals("pgroll", read.engine());
        assertEquals(List.of("01_create_posts", "02_add_title"),
                read.entries().stream().map(SchemaHistory.Entry::version).toList());
        assertEquals(List.of("complete", "active"), read.entries().stream().map(SchemaHistory.Entry::state).toList());
        assertNotNull(read.entries().get(0).completedAt());
        assertNull(read.entries().get(1).completedAt());
    }
}
