package com.lily.cicd.schema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 앱 DB 에 남은 스키마 이력을 읽는다. pgroll 을 켠 DB 는 {@code pgroll.migrations}, 아니면 {@code flyway_schema_history}.
 * 화면에 보여 주려고 읽기만 한다.
 */
public class SchemaHistory {

    private final Function<DbTarget, SchemaDatabase> connector;

    public SchemaHistory() {
        this(SchemaDatabase::new);
    }

    SchemaHistory(Function<DbTarget, SchemaDatabase> connector) {
        this.connector = connector;
    }

    /**
     * @return 오래된 것부터. 이력이 없으면 엔진이 null 이고 목록이 비어 있다
     * @throws SchemaOperationException DB 에 붙지 못함
     */
    public History read(DbTarget target) {
        try (Connection conn = connector.apply(target).connect(); Statement st = conn.createStatement()) {
            if (target.postgres() && pgrollInstalled(st)) {
                return new History("pgroll", pgroll(st));
            }
            List<Entry> flyway = flyway(conn);
            return new History(flyway.isEmpty() ? null : "flyway", flyway);
        } catch (SQLException e) {
            throw new SchemaOperationException("스키마 이력 조회 실패: " + e.getMessage(), e);
        }
    }

    private static boolean pgrollInstalled(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT to_regclass('pgroll.migrations') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** DDL 이벤트로 남은 inferred 행은 빼고, pgroll 로 적용한 것과 baseline 만 */
    private static List<Entry> pgroll(Statement st) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("""
                SELECT name, done, migration_type, created_at FROM pgroll.migrations
                WHERE schema = 'public' AND migration_type IN ('pgroll', 'baseline')
                ORDER BY created_at, name""")) {
            while (rs.next()) {
                String type = rs.getString(3);
                String state = "baseline".equals(type) ? "baseline" : rs.getBoolean(2) ? "complete" : "active";
                entries.add(new Entry(rs.getString(1), null, state, instant(rs.getTimestamp(4))));
            }
        }
        return entries;
    }

    /** 테이블이 없으면 빈 목록 */
    private static List<Entry> flyway(Connection conn) {
        List<Entry> entries = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, description, success, installed_on FROM "
                     + SchemaDatabase.HISTORY_TABLE + " ORDER BY installed_rank")) {
            while (rs.next()) {
                entries.add(new Entry(rs.getString(1), rs.getString(2), rs.getBoolean(3) ? "applied" : "failed",
                        instant(rs.getTimestamp(4))));
            }
        } catch (SQLException e) {
            return List.of();
        }
        return entries;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** @param engine {@code pgroll} / {@code flyway} / null (이력 없음) */
    public record History(String engine, List<Entry> entries) {
    }

    /**
     * @param version     pgroll 마이그레이션 이름 또는 Flyway 버전
     * @param description Flyway 설명. pgroll 은 null
     * @param state       pgroll: active / complete / baseline, Flyway: applied / failed
     * @param startedAt   pgroll start 또는 Flyway 적용 시각. pgroll 은 complete 시각을 남기지 않는다
     */
    public record Entry(String version, String description, String state, Instant startedAt) {
    }
}
