package com.lily.cicd.schema;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSetTest {

    private static MigrationVersion v(String version) {
        return MigrationVersion.fromVersion(version);
    }

    @Test
    void V와_U를_버전별로_나눈다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V1__init.sql", "create table a(id int);",
                "U1__init.sql", "drop table a;",
                "V1_1__col.sql", "alter table a add column b int;"));

        assertFalse(set.isEmpty());
        assertEquals(List.of(v("1"), v("1.1")),
                set.versionedScripts().stream().map(MigrationScript::version).toList());
        assertTrue(set.undo(v("1")).isPresent());
        assertTrue(set.undo(v("1.1")).isEmpty());
        assertEquals(3, set.files().size());
    }

    @Test
    void 비어_있거나_null이면_빈_집합이다() {
        assertTrue(MigrationSet.parse(null).isEmpty());
        assertTrue(MigrationSet.parse(Map.of()).isEmpty());
        assertTrue(MigrationSet.empty().files().isEmpty());
    }

    @Test
    void 규칙_위반은_모아서_알린다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MigrationSet.parse(Map.of(
                "R__views.sql", "create view v as select 1;",
                "init.sql", "select 1;",
                "V2__a.sql", "select 1;",
                "V2__b.sql", "select 1;",
                "U3__c.sql", "select 1;")));

        assertTrue(e.getMessage().contains("R__views.sql"));
        assertTrue(e.getMessage().contains("init.sql"));
        assertTrue(e.getMessage().contains("버전이 같다"));
        assertTrue(e.getMessage().contains("U3__c.sql: 같은 버전의 V 파일이 없다"));
    }

    @Test
    void 합계가_900KiB를_넘으면_거절한다() {
        String big = "x".repeat(MigrationSet.MAX_TOTAL_BYTES);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MigrationSet.parse(Map.of("V1__big.sql", big)));
        assertTrue(e.getMessage().contains("KiB"));
    }

    @Test
    void 적용되지_않은_V만_오름차순으로_고른다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V1__a.sql", "a", "V2__b.sql", "b", "V10__c.sql", "c"));

        List<MigrationScript> pending = set.pending(Set.of(v("1")));

        assertEquals(List.of("V2__b.sql", "V10__c.sql"), pending.stream().map(MigrationScript::fileName).toList());
    }

    @Test
    void 되돌릴_수_없는_이유를_버전별로_알린다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V1__a.sql", "a",
                "V2__b.sql", "-- lily:irreversible\nalter table t drop column c;",
                "V3__c.sql", "c", "U3__c.sql", "undo c"));

        assertTrue(set.undoBlocker(List.of(v("3"))).isEmpty());
        String reason = set.undoBlocker(List.of(v("1"), v("2"), v("4"))).orElseThrow();
        assertTrue(reason.contains("v1: U 스크립트가 없다"));
        assertTrue(reason.contains("v2: V2__b.sql 는 lily:irreversible"));
        assertTrue(reason.contains("v4: 이 릴리스에 스크립트가 없다"));
    }

    @Test
    void U는_버전_내림차순으로_돌려준다() {
        MigrationSet set = MigrationSet.parse(Map.of(
                "V2__a.sql", "a", "U2__a.sql", "ua",
                "V3__b.sql", "b", "U3__b.sql", "ub"));

        assertEquals(List.of("U3__b.sql", "U2__a.sql"),
                set.undoScripts(List.of(v("2"), v("3"))).stream().map(MigrationScript::fileName).toList());
        assertThrows(IllegalStateException.class, () -> set.undoScripts(List.of(v("9"))));
    }

    @Test
    void irreversible_표시는_V에서만_의미가_있다() {
        MigrationScript marked = new MigrationScript("V1__a.sql", MigrationScript.Kind.VERSIONED, v("1"),
                "  --   lily:irreversible  \ndrop table a;");
        MigrationScript inline = new MigrationScript("V2__a.sql", MigrationScript.Kind.VERSIONED, v("2"),
                "drop table a; -- lily:irreversible");
        MigrationScript undo = new MigrationScript("U1__a.sql", MigrationScript.Kind.UNDO, v("1"),
                "-- lily:irreversible");

        assertTrue(marked.irreversible());
        assertFalse(inline.irreversible());
        assertFalse(undo.irreversible());
    }
}
