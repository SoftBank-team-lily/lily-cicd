package com.lily.cicd.schema;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PgrollEnvTest {

    @Test
    void JDBC_URL에는_currentSchema를_DATABASE_URL에는_search_path_옵션을_붙인다() {
        Map<String, String> env = PgrollEnv.withSchema(Map.of(
                "DB_URL", "jdbc:postgresql://h:5432/p_1",
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://h:5432/p_1?sslmode=require",
                "DATABASE_URL", "postgresql://u:pw@h:5432/p_1",
                "DB_PASSWORD", "pw"), "public_02_add_slug");

        assertEquals("jdbc:postgresql://h:5432/p_1?currentSchema=public_02_add_slug", env.get("DB_URL"));
        assertEquals("jdbc:postgresql://h:5432/p_1?sslmode=require&currentSchema=public_02_add_slug",
                env.get("SPRING_DATASOURCE_URL"));
        assertEquals("postgresql://u:pw@h:5432/p_1?options=-c%20search_path%3Dpublic_02_add_slug",
                env.get("DATABASE_URL"));
        assertEquals("public_02_add_slug", env.get(PgrollEnv.SCHEMA_ENV));
        assertEquals("pw", env.get("DB_PASSWORD"));
    }

    @Test
    void 이미_currentSchema가_있으면_새_버전으로_바꾼다() {
        assertEquals("jdbc:postgresql://h/p?a=1&currentSchema=public_03&b=2",
                PgrollEnv.param("jdbc:postgresql://h/p?a=1&currentSchema=public_02&b=2", "currentSchema", "public_03"));
    }

    @Test
    void pgroll_CLI_URL에는_비밀번호를_넣지_않고_sslmode를_붙인다() {
        DbTarget target = new DbTarget("jdbc:postgresql://rds:5432/p_1?ssl=true", "p_1", "secret");

        assertEquals("postgres://p_1@rds:5432/p_1?sslmode=require", PgrollCli.url(target, "require"));
        assertThrows(IllegalStateException.class,
                () -> PgrollCli.url(new DbTarget("jdbc:mysql://h/p", "u", "p"), "require"));
    }

    @Test
    void CLI_출력에서_색_코드와_진행_표시를_지우고_마지막_줄을_쓴다() {
        String raw = "\u001B[96m▀ \u001B[0m Starting...\r\u001B[30;42m SUCCESS \u001B[0m   New version   ready\n\n";

        assertEquals("SUCCESS New version ready", PgrollCli.lastLine(PgrollCli.clean(raw)));
        assertEquals("(출력 없음)", PgrollCli.lastLine(""));
    }

    @Test
    void CLI를_실행하지_못하면_SchemaOperationException을_던진다() {
        PgrollCli cli = new PgrollCli(new PgrollCli.Settings("/없는/pgroll", "disable", 500, 5, 1000, "0s"));
        DbTarget target = new DbTarget("jdbc:postgresql://h:5432/p", "u", "p");

        assertThrows(SchemaOperationException.class, () -> cli.complete(target));
    }
}
