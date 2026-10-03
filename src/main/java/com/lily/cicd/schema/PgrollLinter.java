package com.lily.cicd.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 적용 전 pgroll 마이그레이션 검사. Flyway lint 와 달리 스키마 모양(이름·타입 변경, NOT NULL)은 막지 않는다.
 * pgroll 은 버전마다 스키마를 따로 두므로 그런 변경도 이전 버전을 깨지 않는다.
 * 여기서는 pgroll 이 두 버전 동시 운영이나 롤백 시 행 보존을 보장하지 못하는 연산만 거절한다.
 *
 * <ul>
 *   <li>{@code sql} 연산: 버전 스키마 없이 실제 테이블에 바로 적용돼 이전 버전도 바뀐다</li>
 *   <li>기본값 없는 NOT NULL {@code add_column}: start 가 NOT NULL 검사 제약을 up 트리거보다 먼저 걸어서,
 *       그 사이 이전 버전의 INSERT 가 실패한다 (pgroll 0.16.3 op_add_column.go, 실측 980건 중 1건)</li>
 *   <li>{@code down} 없는 {@code alter_column} 데이터 변환({@code up} 또는 {@code type}): 새 버전이 쓴 값이
 *       이전 컬럼에 반영되지 않아 롤백하면 그 값을 잃는다</li>
 * </ul>
 */
public final class PgrollLinter {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private PgrollLinter() {
    }

    /** @return 위반 목록. 비어 있으면 통과 */
    public static List<String> lint(List<PgrollSet.Migration> migrations) {
        List<String> violations = new ArrayList<>();
        for (PgrollSet.Migration migration : migrations) {
            JsonNode operations;
            try {
                JsonNode root = (migration.file().endsWith(".json") ? JSON : YAML).readTree(migration.body());
                operations = root == null ? null : root.get("operations");
            } catch (JsonProcessingException e) {
                violations.add(migration.file() + ": 읽을 수 없다 — " + e.getOriginalMessage());
                continue;
            }
            if (operations == null || !operations.isArray() || operations.isEmpty()) {
                violations.add(migration.file() + ": operations 목록이 없다");
                continue;
            }
            for (int i = 0; i < operations.size(); i++) {
                Iterator<Map.Entry<String, JsonNode>> fields = operations.get(i).fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> op = fields.next();
                    String where = migration.file() + " operations[" + i + "] " + op.getKey();
                    check(where, op.getKey(), op.getValue(), violations);
                }
            }
        }
        return violations;
    }

    private static void check(String where, String name, JsonNode op, List<String> violations) {
        switch (name) {
            case "sql" -> violations.add(where
                    + ": sql 연산은 버전 스키마 없이 바로 적용돼 이전 버전도 바뀐다. pgroll 연산으로 바꿔야 한다");
            case "add_column" -> {
                JsonNode column = op.path("column");
                boolean nullable = column.path("nullable").asBoolean(false);
                if (!nullable && !column.hasNonNull("default")) {
                    violations.add(where + " " + column.path("name").asText("?")
                            + ": NOT NULL 컬럼은 default 가 있어야 한다 (없으면 start 순간 이전 버전의 INSERT 가 실패한다). "
                            + "nullable: true 로 두거나 default 를 준다");
                }
            }
            case "alter_column" -> {
                boolean converts = op.hasNonNull("up") || op.hasNonNull("type");
                if (converts && !op.hasNonNull("down")) {
                    violations.add(where + " " + op.path("column").asText("?")
                            + ": up 이나 type 이 있으면 down 도 있어야 한다 (없으면 롤백할 때 새 버전이 쓴 값을 잃는다)");
                }
            }
            default -> {
                // 나머지 pgroll 연산은 버전 스키마로 처리된다
            }
        }
    }
}
