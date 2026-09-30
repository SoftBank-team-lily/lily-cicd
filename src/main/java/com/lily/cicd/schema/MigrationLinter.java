package com.lily.cicd.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 아직 적용되지 않은 V 가 blue-green 에서 안전한지 본다.
 *
 * <p>새 슬롯이 Ready 가 될 때까지 이전 슬롯이 새 스키마 위에서 트래픽을 받는다.
 * 그래서 이전 코드의 쿼리를 깨는 변경(이름·타입 변경, 기본값 없는 NOT NULL)은 막고,
 * 데이터가 사라지는 변경은 {@code -- lily:irreversible} 로 표시한 V 에서만 허용한다.
 * 롤백 가능성을 배포 시점에 보장하려고 V 마다 U 를 요구한다.
 *
 * <p>SQL 파서가 아니라 주석과 문자열을 걷어낸 뒤 문장 단위로 정규식을 본다.
 */
public final class MigrationLinter {

    private static final Pattern RENAME = Pattern.compile("\\bRENAME\\b");
    private static final Pattern TYPE_CHANGE = Pattern.compile(
            "\\bALTER\\s+TABLE\\b.*\\b(ALTER\\s+(COLUMN\\s+)?\\S+\\s+(SET\\s+DATA\\s+)?TYPE|MODIFY|CHANGE)\\b");
    private static final Pattern SET_NOT_NULL = Pattern.compile("\\bSET\\s+NOT\\s+NULL\\b");
    private static final Pattern ADD_NOT_NULL = Pattern.compile(
            "\\bALTER\\s+TABLE\\b.*\\bADD\\s+(COLUMN\\s+)?(?!CONSTRAINT\\b).*\\bNOT\\s+NULL\\b");
    private static final Pattern DEFAULT = Pattern.compile("\\bDEFAULT\\b");
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\bDROP\\s+(TABLE|COLUMN|SCHEMA|DATABASE)\\b"
                    + "|\\bALTER\\s+TABLE\\s+\\S+\\s+DROP\\s+(?!CONSTRAINT\\b|INDEX\\b|KEY\\b|FOREIGN\\b|PRIMARY\\b|CHECK\\b|DEFAULT\\b)"
                    + "|\\bTRUNCATE\\b");

    private MigrationLinter() {
    }

    /** @return 위반 목록. 비어 있으면 통과 */
    public static List<String> lint(List<MigrationScript> pending, MigrationSet set) {
        List<String> violations = new ArrayList<>();
        for (MigrationScript script : pending) {
            boolean irreversible = script.irreversible();
            if (!irreversible && set.undo(script.version()).isEmpty()) {
                violations.add(script.fileName() + ": U" + script.version() + "__*.sql 이 없다. 되돌릴 수 없는 변경이면 '"
                        + MigrationScript.IRREVERSIBLE_MARKER + "' 를 넣는다");
            }
            for (String statement : statements(script.sql())) {
                String rule = violation(statement, irreversible);
                if (rule != null) {
                    violations.add(script.fileName() + ": " + rule + " — " + abbreviate(statement));
                }
            }
        }
        return violations;
    }

    private static String violation(String s, boolean irreversible) {
        if (RENAME.matcher(s).find()) {
            return "이름 변경은 이전 슬롯의 쿼리를 깨뜨린다. 새 컬럼 추가 → 코드 전환 → 옛 컬럼 삭제로 나눈다";
        }
        if (TYPE_CHANGE.matcher(s).find()) {
            return "컬럼 타입 변경은 이전 슬롯의 쿼리를 깨뜨린다. 새 컬럼을 추가해 옮긴다";
        }
        if (SET_NOT_NULL.matcher(s).find()) {
            return "기존 컬럼에 NOT NULL 을 걸면 이전 슬롯의 INSERT 가 깨질 수 있다";
        }
        if (ADD_NOT_NULL.matcher(s).find() && !DEFAULT.matcher(s).find()) {
            return "NOT NULL 컬럼을 추가하려면 DEFAULT 가 필요하다 (이전 슬롯의 INSERT 는 이 컬럼을 모른다)";
        }
        if (!irreversible && DESTRUCTIVE.matcher(s).find()) {
            return "데이터가 사라지는 변경은 U 로 복구할 수 없다. '" + MigrationScript.IRREVERSIBLE_MARKER
                    + "' 로 표시한 contract 마이그레이션에서만 허용한다";
        }
        return null;
    }

    /** 주석과 문자열 리터럴을 지우고 ; 로 나눈 뒤 대문자·공백 하나로 정리한다 */
    static List<String> statements(String sql) {
        String stripped = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("'(?:[^']|'')*'", "''");
        List<String> result = new ArrayList<>();
        for (String part : stripped.split(";")) {
            String normalized = part.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String abbreviate(String statement) {
        return statement.length() <= 80 ? statement : statement.substring(0, 77) + "...";
    }
}
