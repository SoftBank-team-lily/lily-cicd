package com.lily.cicd.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 사용자 앱 DB 하나에 대한 스키마 작업. 호출마다 연결을 새로 열고 닫는다.
 *
 * <p>적용은 Flyway 가, 되돌리기와 dry-run 은 JDBC 로 U 스크립트를 직접 실행한다
 * (Flyway Community 에는 undo 가 없다). 되돌린 버전은 {@code flyway_schema_history} 에서 지워서
 * 고친 V 를 같은 버전으로 다시 배포할 수 있게 한다.
 */
public class SchemaDatabase {

    static final String HISTORY_TABLE = "flyway_schema_history";
    static final String BACKUP_PREFIX = "lily_bak_";
    static final int BACKUPS_TO_KEEP = 3;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final DbTarget target;

    public SchemaDatabase(DbTarget target) {
        this.target = target;
    }

    /** PostgreSQL 은 DDL 도 트랜잭션 안에서 되돌릴 수 있다. MySQL 은 DDL 이 자동 커밋된다 */
    public boolean transactionalDdl() {
        return target.postgres();
    }

    /** history 의 성공한 버전. 테이블이 없으면 빈 집합 */
    public Set<MigrationVersion> appliedVersions() {
        try (Connection conn = connect()) {
            if (!historyExists(conn)) {
                return Set.of();
            }
            Set<MigrationVersion> versions = new HashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT version FROM " + HISTORY_TABLE + " WHERE success = true AND version IS NOT NULL")) {
                while (rs.next()) {
                    versions.add(MigrationVersion.fromVersion(rs.getString(1)));
                }
            }
            return versions;
        } catch (SQLException e) {
            throw new SchemaOperationException("history 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 스크립트를 순서대로 실행하고 마지막에 전부 취소한다 (PostgreSQL 전용).
     * 대상 V → U 역순 → V 재적용이 모두 통과하면 U 가 V 를 실제로 되돌린다는 뜻이다.
     */
    public void dryRun(List<MigrationScript> steps) {
        if (!transactionalDdl()) {
            throw new IllegalStateException("dry-run 은 PostgreSQL 에서만 가능하다");
        }
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL lock_timeout = '5s'");
                for (MigrationScript step : steps) {
                    execute(st, step);
                }
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new SchemaOperationException("dry-run 연결 실패: " + e.getMessage(), e);
        }
    }

    /** V 파일만 Flyway 로 적용한다. PostgreSQL 은 대상 전체가 한 트랜잭션이다 (group) */
    public List<MigrationVersion> migrate(MigrationSet scripts) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lily-migrations-");
            for (MigrationScript script : scripts.versionedScripts()) {
                Files.writeString(dir.resolve(script.fileName()), script.sql());
            }
            MigrateResult result = Flyway.configure(getClass().getClassLoader())
                    .dataSource(target.url(), target.username(), target.password())
                    .locations("filesystem:" + dir.toAbsolutePath())
                    // dry-run 과 같은 SQL 을 실행하려고 ${} 치환을 끈다
                    .placeholderReplacement(false)
                    .group(true)
                    .initSql(target.postgres() ? "SET lock_timeout = '5s'" : "SET SESSION lock_wait_timeout = 5")
                    .connectRetries(2)
                    .load()
                    .migrate();
            return result.migrations.stream().map(m -> MigrationVersion.fromVersion(m.version)).toList();
        } catch (FlywayException e) {
            throw new SchemaOperationException("flyway migrate 실패: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * U 스크립트를 주어진 순서(버전 내림차순)로 실행하고 history 행을 지운다.
     * PostgreSQL 은 전체가 한 트랜잭션이라 하나라도 실패하면 아무것도 바뀌지 않는다.
     * MySQL 은 버전마다 반영되므로 실패하면 그 앞 버전까지만 되돌아간다.
     */
    public void undo(List<MigrationScript> undoScripts) {
        try (Connection conn = connect()) {
            boolean tx = transactionalDdl();
            conn.setAutoCommit(!tx);
            List<String> done = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 PreparedStatement delete = conn.prepareStatement(
                         "DELETE FROM " + HISTORY_TABLE + " WHERE version = ?")) {
                st.execute(tx ? "SET LOCAL lock_timeout = '5s'" : "SET SESSION lock_wait_timeout = 5");
                for (MigrationScript script : undoScripts) {
                    execute(st, script);
                    delete.setString(1, script.version().getVersion());
                    delete.executeUpdate();
                    done.add("v" + script.version());
                }
                if (tx) {
                    conn.commit();
                }
            } catch (SchemaOperationException e) {
                if (tx) {
                    conn.rollback();
                    throw e;
                }
                throw new SchemaOperationException(e.getMessage() + " (이미 되돌린 버전: " + done + ")", e);
            }
        } catch (SQLException e) {
            throw new SchemaOperationException("되돌리기 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 현재 스키마의 테이블 전체를 {@code lily_bak_{시각}} 스키마로 복사한다 (PostgreSQL 전용).
     * U 가 지우는 컬럼 값의 마지막 사본이다. 최근 {@value #BACKUPS_TO_KEEP}개만 남긴다.
     *
     * @return 만든 스키마 이름
     */
    public String backup() {
        if (!target.postgres()) {
            throw new IllegalStateException("백업은 PostgreSQL 에서만 가능하다");
        }
        String backupSchema = BACKUP_PREFIX + ZonedDateTime.now(ZoneOffset.UTC).format(BACKUP_TIME);
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                String source = single(st, "SELECT current_schema()");
                List<String> tables = list(st, "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' ORDER BY table_name");
                st.execute("CREATE SCHEMA " + quote(backupSchema));
                for (String table : tables) {
                    st.execute("CREATE TABLE " + quote(backupSchema) + "." + quote(table)
                            + " AS TABLE " + quote(source) + "." + quote(table));
                }
                List<String> old = list(st, "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'lily\\_bak\\_%' "
                        + "ORDER BY nspname DESC OFFSET " + BACKUPS_TO_KEEP);
                for (String schema : old) {
                    st.execute("DROP SCHEMA " + quote(schema) + " CASCADE");
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
            return backupSchema;
        } catch (SQLException e) {
            throw new SchemaOperationException("백업 실패: " + e.getMessage(), e);
        }
    }

    private static void execute(Statement st, MigrationScript script) {
        try {
            st.execute(script.sql());
        } catch (SQLException e) {
            throw new SchemaOperationException(script.fileName() + " 실행 실패: " + e.getMessage(), e);
        }
    }

    private boolean historyExists(Connection conn) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), conn.getSchema(), HISTORY_TABLE, null)) {
            return rs.next();
        }
    }

    /**
     * DriverManager 대신 드라이버를 직접 만든다. Spring Boot 실행 jar 의 클래스로더에서 드라이버를 못 찾는 문제를 피한다.
     * MySQL 은 U 스크립트 한 파일에 문장이 여러 개라 allowMultiQueries 가 필요하다.
     */
    Connection connect() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", target.username());
        props.setProperty("password", target.password());
        String driverClass;
        if (target.postgres()) {
            driverClass = "org.postgresql.Driver";
            props.setProperty("connectTimeout", "5");
        } else {
            driverClass = "com.mysql.cj.jdbc.Driver";
            props.setProperty("connectTimeout", "5000");
            props.setProperty("allowMultiQueries", "true");
        }
        try {
            Driver driver = (Driver) Class.forName(driverClass, true, getClass().getClassLoader())
                    .getDeclaredConstructor().newInstance();
            Connection conn = driver.connect(target.url(), props);
            if (conn == null) {
                throw new SQLException("드라이버가 URL 을 받지 않았다: " + target);
            }
            return conn;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("JDBC 드라이버를 불러오지 못했다: " + driverClass, e);
        }
    }

    private static String single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> list(Statement st, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 임시 폴더라 남아도 해가 없다
        }
    }
}
