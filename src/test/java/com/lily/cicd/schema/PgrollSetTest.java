package com.lily.cicd.schema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgrollSetTest {

    @Test
    void yaml과_json이면_pgroll이고_sql이면_아니다() {
        assertTrue(PgrollSet.isPgroll(Map.of("01_a.yaml", "x")));
        assertTrue(PgrollSet.isPgroll(Map.of("01_a.json", "x")));
        assertFalse(PgrollSet.isPgroll(Map.of("V1__a.sql", "x")));
        assertFalse(PgrollSet.isPgroll(Map.of()));
        assertFalse(PgrollSet.isPgroll(null));
    }

    @Test
    void pgroll_파일과_sql을_섞으면_거절한다() {
        Map<String, String> files = Map.of("01_a.yaml", "x", "V1__a.sql", "y");

        assertThrows(IllegalArgumentException.class, () -> PgrollSet.isPgroll(files));
    }

    @Test
    void 번호_순으로_정렬하고_확장자를_뺀_이름을_마이그레이션_이름으로_쓴다() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("10_rename.yml", "b");
        files.put("2_add_slug.yaml", "a");

        PgrollSet set = PgrollSet.parse(files);

        assertEquals(List.of("2_add_slug", "10_rename"), set.migrations().stream().map(PgrollSet.Migration::name).toList());
        assertEquals(List.of("2_add_slug.yaml", "10_rename.yml"), List.copyOf(set.files().keySet()));
        assertEquals("public_2_add_slug", PgrollSet.versionSchema("2_add_slug"));
    }

    @Test
    void 파일명_규칙_번호_중복_빈_내용_긴_이름을_모아서_알린다() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("01_ok.yaml", "x");
        files.put("01_dup.yaml", "x");
        files.put("Add-Slug.yaml", "x");
        files.put("02_empty.yaml", " ");
        files.put("03_" + "a".repeat(60) + ".yaml", "x");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PgrollSet.parse(files));

        assertTrue(e.getMessage().contains("01_dup.yaml: 번호 1"), e.getMessage());
        assertTrue(e.getMessage().contains("Add-Slug.yaml: 파일명"), e.getMessage());
        assertTrue(e.getMessage().contains("02_empty.yaml: 내용이 비어"), e.getMessage());
        assertTrue(e.getMessage().contains("자 이하여야"), e.getMessage());
    }

    @Test
    void 합계가_900KiB를_넘으면_거절한다() {
        Map<String, String> files = Map.of("01_big.yaml", "x".repeat(MigrationSet.MAX_TOTAL_BYTES + 1));

        assertThrows(IllegalArgumentException.class, () -> PgrollSet.parse(files));
    }

    @Test
    void 비어_있거나_null이면_빈_집합이다() {
        assertTrue(PgrollSet.parse(null).isEmpty());
        assertTrue(PgrollSet.parse(Map.of()).isEmpty());
        assertTrue(PgrollSet.empty().isEmpty());
    }
}
