package com.lily.cicd.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * pgroll 로 무중단 스키마 변경을 한다 (expand/contract). 순서와 규칙은 docs/schema-migration.md 7 절.
 *
 * <pre>
 * 배포   이전 active complete → (이력 없으면 baseline) → start (active 로 남김) → 새 슬롯은 public_{새 이름}
 * 실패   트래픽을 옮기기 전이면 rollback (새 버전 스키마·임시 컬럼·트리거만 지운다)
 * 롤백   롤백 창 안이면 rollback. 그동안 새 버전이 쓴 값은 down 트리거로 옛 컬럼에 이미 있다
 * 종료   롤백 창이 지나거나 다음 배포가 오면 complete (옛 컬럼·이전 버전 스키마 삭제)
 * </pre>
 *
 * <p>상태는 DB 의 {@code pgroll.migrations} 가 기준이다. 이 클래스는 상태를 갖지 않는다.
 * pgroll init 은 이벤트 트리거 때문에 관리자 권한이 필요해서 DB 모듈(lily-db-provisioner)이 한다.
 */
public class PgrollMigrator {

    /** 이력 없이 테이블만 있는 DB 를 처음 pgroll 로 넘길 때 남기는 이름 */
    static final String BASELINE = "00000_lily_baseline";

    private final PgrollCli cli;
    private final Function<DbTarget, SchemaDatabase> connector;

    public PgrollMigrator(PgrollCli cli) {
        this(cli, SchemaDatabase::new);
    }

    PgrollMigrator(PgrollCli cli, Function<DbTarget, SchemaDatabase> connector) {
        this.cli = cli;
        this.connector = connector;
    }

    /**
     * @param oldVersionLive 이전 슬롯이 아직 이 DB 를 쓰고 있다. 그러면 새 마이그레이션은 하나만 받는다
     *                       (둘 이상이면 앞의 것을 complete 해야 해서 이전 슬롯이 쓰는 스키마가 사라진다)
     * @throws IllegalArgumentException 규칙 위반. DB 는 바뀌지 않았다
     * @throws IllegalStateException    pgroll 이 켜지지 않은 DB
     * @throws SchemaOperationException pgroll 명령 실패
     */
    public PgrollChange migrate(Map<String, String> databaseEnv, PgrollSet set, boolean oldVersionLive,
                                List<String> logs) {
        DbTarget target = postgres(databaseEnv);
        State state = state(target);
        if (!state.installed()) {
            throw new IllegalStateException("이 DB 에는 pgroll 이 켜져 있지 않다");
        }
        Set<String> wanted = new HashSet<>();
        set.migrations().forEach(m -> wanted.add(m.name()));
        String from = state.latest().orElse(null);
        List<PgrollSet.Migration> pending = pending(set, state);
        // 검사를 모두 끝낸 뒤에 DB 를 바꾼다
        if (pending.isEmpty()) {
            if (from == null) {
                throw new IllegalArgumentException("pgroll 마이그레이션이 하나도 적용되지 않았다");
            }
            if (!wanted.contains(from)) {
                throw new IllegalArgumentException("DB 의 최신 마이그레이션 " + from + " 이 이번 커밋에 없다");
            }
            // 코드만 바뀐 배포. 진행 중인 마이그레이션의 롤백 창은 그대로 둔다
            logs.add("schema: pgroll up to date at " + from + state.active().map(a -> " (active)").orElse(""));
            return new PgrollChange(from, from, false, null);
        }
        if (pending.size() > 1 && oldVersionLive) {
            throw new IllegalArgumentException("이전 버전이 실행 중이면 pgroll 마이그레이션은 배포마다 하나만 적용한다. 대기 중: "
                    + pending.stream().map(PgrollSet.Migration::name).toList());
        }
        String completed = null;
        if (state.active().isPresent()) {
            // 이전 배포의 롤백 창을 닫는다. 그보다 앞 버전 스키마(지금은 replica 0 인 슬롯이 쓰던 것)가 사라진다
            completed = state.active().get();
            cli.complete(target);
            logs.add("schema: pgroll complete " + completed + " (이전 배포의 롤백 창 종료)");
        }
        if (state.rows().stream().noneMatch(r -> "pgroll".equals(r.type()) || "baseline".equals(r.type()))
                && state.hasTables()) {
            withFiles(List.of(), dir -> cli.baseline(target, BASELINE, dir));
            logs.add("schema: pgroll baseline " + BASELINE + " (기존 테이블을 출발점으로 기록)");
        }
        for (int i = 0; i < pending.size(); i++) {
            PgrollSet.Migration migration = pending.get(i);
            boolean last = i == pending.size() - 1;
            String out = withFiles(List.of(migration), dir -> cli.start(target, dir.resolve(migration.file()), !last));
            logs.add("schema: pgroll start " + migration.name() + (last ? " (active)" : " --complete") + " — " + out);
        }
        String to = pending.get(pending.size() - 1).name();
        return new PgrollChange(from, to, true, completed);
    }

    /** 진행 중(start 했고 complete 전)인 마이그레이션 */
    public Optional<String> active(Map<String, String> databaseEnv) {
        return state(postgres(databaseEnv)).active();
    }

    /**
     * 앱이 접속할 최신 버전. pgroll 이 켜져 있지 않거나 pgroll 마이그레이션이 없으면 빈 값.
     * 마이그레이션 없이 배포되는 커밋도 버전 스키마로 접속해야 해서 쓴다.
     */
    public Optional<String> latest(Map<String, String> databaseEnv) {
        State state = state(postgres(databaseEnv));
        return state.installed() ? state.latest() : Optional.empty();
    }

    /**
     * 진행 중인 마이그레이션을 되돌린다. 새 버전 스키마와 임시 컬럼·트리거만 지운다. 행은 그대로다.
     *
     * @param expected 이 이름이 active 일 때만 되돌린다
     * @throws IllegalStateException active 가 없거나 다르다 (이미 complete 됐다)
     */
    public void rollback(Map<String, String> databaseEnv, String expected, List<String> logs) {
        DbTarget target = postgres(databaseEnv);
        requireActive(target, expected);
        cli.rollback(target);
        logs.add("schema: pgroll rollback " + expected + " (새 버전 스키마 제거, 행 유지)");
    }

    /**
     * @param expected 이 이름이 active 일 때만 끝낸다
     * @throws IllegalStateException active 가 없거나 다르다
     */
    public void complete(Map<String, String> databaseEnv, String expected, List<String> logs) {
        DbTarget target = postgres(databaseEnv);
        requireActive(target, expected);
        cli.complete(target);
        logs.add("schema: pgroll complete " + expected);
    }

    private void requireActive(DbTarget target, String expected) {
        Optional<String> active = state(target).active();
        if (active.isEmpty() || !active.get().equals(expected)) {
            throw new IllegalStateException("pgroll 진행 중인 마이그레이션이 " + expected + " 가 아니다 (현재: "
                    + active.orElse("없음, 이미 complete 됨") + ")");
        }
    }

    private static List<PgrollSet.Migration> pending(PgrollSet set, State state) {
        return set.migrations().stream().filter(m -> !state.names().contains(m.name())).toList();
    }

    private static DbTarget postgres(Map<String, String> databaseEnv) {
        DbTarget target = DbTarget.from(databaseEnv);
        if (!target.postgres()) {
            throw new IllegalArgumentException("pgroll 마이그레이션은 postgres 만 지원한다");
        }
        return target;
    }

    /** CLI 는 파일 경로를 받는다. 이번 명령에만 쓰는 임시 폴더에 쓰고 지운다 */
    private static <T> T withFiles(List<PgrollSet.Migration> migrations, Function<Path, T> action) {
        Path dir;
        try {
            dir = Files.createTempDirectory("pgroll-");
            for (PgrollSet.Migration m : migrations) {
                Files.writeString(dir.resolve(m.file()), m.body(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("pgroll 마이그레이션 파일을 쓰지 못함", e);
        }
        try {
            return action.apply(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // 임시 폴더라 남아도 다음 재시작에 사라진다
            }
        }
    }

    State state(DbTarget target) {
        try (Connection conn = connector.apply(target).connect(); Statement st = conn.createStatement()) {
            boolean installed;
            try (ResultSet rs = st.executeQuery("SELECT to_regclass('pgroll.migrations') IS NOT NULL")) {
                installed = rs.next() && rs.getBoolean(1);
            }
            boolean hasTables;
            try (ResultSet rs = st.executeQuery("""
                    SELECT EXISTS (SELECT 1 FROM information_schema.tables
                                   WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history')""")) {
                hasTables = rs.next() && rs.getBoolean(1);
            }
            if (!installed) {
                return new State(false, hasTables, List.of());
            }
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT name, done, migration_type FROM pgroll.migrations
                    WHERE schema = 'public' ORDER BY created_at, name""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getBoolean(2), rs.getString(3)));
                }
            }
            return new State(true, hasTables, rows);
        } catch (SQLException e) {
            throw new SchemaOperationException("pgroll 상태 조회 실패: " + e.getMessage(), e);
        }
    }

    record Row(String name, boolean done, String type) {
    }

    /** @param rows pgroll.migrations 의 public 스키마 행 (inferred 포함) */
    record State(boolean installed, boolean hasTables, List<Row> rows) {

        /** pgroll 로 적용한 것 (DDL 이벤트로 기록된 inferred 와 baseline 은 빼고) */
        List<Row> applied() {
            return rows.stream().filter(r -> "pgroll".equals(r.type())).toList();
        }

        Set<String> names() {
            Set<String> names = new HashSet<>();
            rows.forEach(r -> names.add(r.name()));
            return names;
        }

        Optional<String> active() {
            return applied().stream().filter(r -> !r.done()).map(Row::name).reduce((a, b) -> b);
        }

        Optional<String> latest() {
            return applied().stream().map(Row::name).reduce((a, b) -> b);
        }
    }

    /**
     * @param from      배포 전 최신 pgroll 마이그레이션. 처음이면 null
     * @param to        새 슬롯이 접속할 마이그레이션 (버전 스키마 public_{to})
     * @param started   이번 배포가 마이그레이션을 시작했다 (실패하면 rollback 대상)
     * @param completed 이번 배포가 시작 전에 끝낸 이전 마이그레이션. 없으면 null
     */
    public record PgrollChange(String from, String to, boolean started, String completed) {
    }
}
