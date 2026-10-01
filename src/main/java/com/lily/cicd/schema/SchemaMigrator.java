package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 배포와 롤백에서 스키마를 옮긴다. 순서와 규칙은 docs/schema-migration.md.
 *
 * <p>DB 접속 정보는 호출마다 받는다 (lily-db-provisioner 가 앱에 넣는 env). 상태를 갖지 않는다.
 */
public class SchemaMigrator {

    private final Function<DbTarget, SchemaDatabase> connector;

    public SchemaMigrator() {
        this(SchemaDatabase::new);
    }

    SchemaMigrator(Function<DbTarget, SchemaDatabase> connector) {
        this.connector = connector;
    }

    /**
     * 적용 대상 V 를 lint → dry-run → migrate 한다.
     *
     * @throws IllegalArgumentException lint 위반. DB 는 바뀌지 않았다
     * @throws SchemaOperationException dry-run 이나 migrate 실패. migrate 가 일부 반영됐으면 되돌린 뒤 던진다
     */
    public SchemaChange migrate(Map<String, String> databaseEnv, MigrationSet scripts, List<String> logs) {
        if (scripts.isEmpty()) {
            return SchemaChange.NONE;
        }
        SchemaDatabase db = connector.apply(DbTarget.from(databaseEnv));
        Set<MigrationVersion> applied = db.appliedVersions();
        MigrationVersion from = latest(applied);
        List<MigrationScript> pending = scripts.pending(applied);
        if (pending.isEmpty()) {
            logs.add("schema: up to date at " + SchemaVersions.format(from));
            return new SchemaChange(from, from, List.of());
        }

        // 첫 릴리스는 되돌아갈 이전 릴리스가 없다. U 를 요구하지 않는다 (MigrationLinter)
        boolean firstRelease = applied.isEmpty();
        List<String> violations = MigrationLinter.lint(pending, scripts, firstRelease);
        if (!violations.isEmpty()) {
            violations.forEach(v -> logs.add("schema: lint " + v));
            throw new IllegalArgumentException("마이그레이션 규칙 위반: " + String.join("; ", violations));
        }
        logs.add("schema: lint ok " + names(pending));
        List<String> withoutUndo = pending.stream()
                .filter(script -> !script.irreversible() && scripts.undo(script.version()).isEmpty())
                .map(MigrationScript::fileName)
                .toList();
        if (!withoutUndo.isEmpty()) {
            logs.add("schema: first release, U 없이 적용 " + withoutUndo + " (이 배포가 실패하면 스키마는 남는다)");
        }

        if (db.transactionalDdl()) {
            db.dryRun(dryRunSteps(pending, scripts));
            logs.add("schema: dry-run ok (V → U → V, rolled back)");
        } else {
            logs.add("schema: dry-run skipped (mysql DDL 은 트랜잭션으로 되돌릴 수 없다)");
        }

        try {
            db.migrate(scripts);
        } catch (SchemaOperationException e) {
            Set<MigrationVersion> partial = new HashSet<>(db.appliedVersions());
            partial.removeAll(applied);
            if (!partial.isEmpty()) {
                logs.add("schema: migrate 가 일부만 반영됨 " + partial + ", 되돌린다");
                revertQuietly(db, scripts, partial, logs);
            }
            throw e;
        }
        Set<MigrationVersion> after = db.appliedVersions();
        List<MigrationVersion> newlyApplied = after.stream().filter(v -> !applied.contains(v)).sorted().toList();
        MigrationVersion to = latest(after);
        logs.add("schema: migrated " + SchemaVersions.format(from) + " -> " + SchemaVersions.format(to)
                + " applied=" + newlyApplied);
        return new SchemaChange(from, to, newlyApplied);
    }

    /** DB 에 적용된 버전 중 target 보다 높은 것. 롤백 사전 검사에 쓴다 */
    public List<MigrationVersion> versionsAbove(Map<String, String> databaseEnv, MigrationVersion target) {
        return above(connector.apply(DbTarget.from(databaseEnv)).appliedVersions(), target);
    }

    /**
     * target 버전까지 U 로 되돌린다.
     *
     * @param backup PostgreSQL 이면 되돌리기 전에 테이블을 백업 스키마에 복사한다
     * @throws IllegalStateException    되돌릴 수 없는 버전이 있다. DB 는 바뀌지 않았다
     * @throws SchemaOperationException U 실행 실패
     */
    public void rollback(Map<String, String> databaseEnv, MigrationSet scripts, MigrationVersion target,
                         boolean backup, List<String> logs) {
        SchemaDatabase db = connector.apply(DbTarget.from(databaseEnv));
        List<MigrationVersion> versions = above(db.appliedVersions(), target);
        if (versions.isEmpty()) {
            logs.add("schema: already at " + SchemaVersions.format(target));
            return;
        }
        Optional<String> blocker = scripts.undoBlocker(versions);
        if (blocker.isPresent()) {
            throw new IllegalStateException("스키마를 되돌릴 수 없다: " + blocker.get());
        }
        if (backup) {
            if (db.transactionalDdl()) {
                logs.add("schema: backup " + db.backup());
            } else {
                logs.add("schema: backup skipped (mysql)");
            }
        }
        List<MigrationScript> undo = scripts.undoScripts(versions);
        db.undo(undo);
        logs.add("schema: reverted " + names(undo) + " -> now " + SchemaVersions.format(target));
    }

    private void revertQuietly(SchemaDatabase db, MigrationSet scripts, Collection<MigrationVersion> versions,
                               List<String> logs) {
        Optional<String> blocker = scripts.undoBlocker(versions);
        if (blocker.isPresent()) {
            logs.add("schema: 되돌리지 못함 — " + blocker.get());
            return;
        }
        try {
            db.undo(scripts.undoScripts(versions));
            logs.add("schema: reverted " + versions);
        } catch (RuntimeException e) {
            logs.add("schema: 되돌리기 실패 — " + e.getMessage());
        }
    }

    /**
     * 대상 V 전부 → (마지막 irreversible 이후의) U 역순 → 같은 V 다시.
     * irreversible 인 V 는 되돌리지 않으므로 그 이전 버전의 U 도 검증 대상에서 뺀다.
     */
    static List<MigrationScript> dryRunSteps(List<MigrationScript> pending, MigrationSet scripts) {
        int lastIrreversible = -1;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).irreversible()) {
                lastIrreversible = i;
            }
        }
        // U 가 없는 V(첫 릴리스)는 되돌리는 단계를 건너뛴다. 이후 릴리스는 lint 가 U 를 보장한다
        List<MigrationScript> reversible = pending.subList(lastIrreversible + 1, pending.size()).stream()
                .filter(script -> scripts.undo(script.version()).isPresent())
                .toList();
        List<MigrationScript> steps = new ArrayList<>(pending);
        steps.addAll(scripts.undoScripts(reversible.stream().map(MigrationScript::version).toList()));
        steps.addAll(reversible);
        return steps;
    }

    private static List<MigrationVersion> above(Set<MigrationVersion> applied, MigrationVersion target) {
        return applied.stream().filter(v -> v.compareTo(target) > 0).sorted(Comparator.reverseOrder()).toList();
    }

    private static MigrationVersion latest(Set<MigrationVersion> versions) {
        return versions.stream().max(Comparator.naturalOrder()).orElse(MigrationVersion.EMPTY);
    }

    private static List<String> names(List<MigrationScript> scripts) {
        return scripts.stream().map(MigrationScript::fileName).toList();
    }
}
