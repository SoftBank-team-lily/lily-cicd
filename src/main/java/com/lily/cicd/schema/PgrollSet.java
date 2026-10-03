package com.lily.cicd.schema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한 릴리스의 pgroll 마이그레이션 파일 ({@code NN_설명.yaml|yml|json}). 앱 레포 {@code db/pgroll} 에 둔다.
 *
 * <p>파일명(확장자 제외)이 pgroll 마이그레이션 이름이고, 앱은 {@code public_{이름}} 버전 스키마로 접속한다.
 * 배포 요청의 {@code migrations} 에 SQL 대신 이 파일들이 오면 pgroll 로 적용한다. 둘을 섞을 수 없다.
 */
public final class PgrollSet {

    /** 버전 스키마 이름(public_ + 이름)이 Postgres 식별자 한계 63 바이트 안에 들어가야 한다 */
    static final int MAX_NAME = 56;
    private static final Pattern NAME = Pattern.compile("(\\d+)_([a-z0-9_]+)\\.(ya?ml|json)");
    private static final PgrollSet EMPTY = new PgrollSet(List.of());

    private final List<Migration> migrations;

    private PgrollSet(List<Migration> migrations) {
        this.migrations = migrations;
    }

    public static PgrollSet empty() {
        return EMPTY;
    }

    /**
     * pgroll 파일이 하나라도 있으면 true. SQL 과 섞여 있으면 400.
     *
     * @throws IllegalArgumentException pgroll 파일과 SQL 파일이 같이 왔다
     */
    public static boolean isPgroll(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return false;
        }
        long pgroll = files.keySet().stream().filter(PgrollSet::pgrollFile).count();
        if (pgroll > 0 && pgroll < files.size()) {
            throw new IllegalArgumentException("pgroll 마이그레이션(.yaml/.json)과 SQL 마이그레이션을 같이 보낼 수 없다");
        }
        return pgroll > 0;
    }

    /**
     * 파일명 규칙, 번호 중복, 이름 길이, 합계 크기를 본다. 위반은 모아서 한 번에 알린다.
     *
     * @throws IllegalArgumentException 규칙 위반 (배포 요청 400)
     */
    public static PgrollSet parse(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return EMPTY;
        }
        List<String> errors = new ArrayList<>();
        List<Migration> parsed = new ArrayList<>();
        Map<Long, String> numbers = new HashMap<>();
        long bytes = 0;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String file = entry.getKey() == null ? "" : entry.getKey();
            String body = entry.getValue() == null ? "" : entry.getValue();
            bytes += file.getBytes(StandardCharsets.UTF_8).length + body.getBytes(StandardCharsets.UTF_8).length;
            Matcher m = NAME.matcher(file);
            if (!m.matches()) {
                errors.add(file + ": 파일명은 {번호}_{소문자_설명}.yaml 또는 .json 이어야 한다");
                continue;
            }
            String name = file.substring(0, file.lastIndexOf('.'));
            if (name.length() > MAX_NAME) {
                errors.add(file + ": 이름은 " + MAX_NAME + "자 이하여야 한다 (버전 스키마 public_{이름} 이 63자를 넘는다)");
                continue;
            }
            if (body.isBlank()) {
                errors.add(file + ": 내용이 비어 있다");
                continue;
            }
            long number = Long.parseLong(m.group(1));
            String previous = numbers.putIfAbsent(number, file);
            if (previous != null) {
                errors.add(file + ": 번호 " + number + " 가 " + previous + " 와 겹친다");
                continue;
            }
            parsed.add(new Migration(number, name, file, body));
        }
        if (bytes > MigrationSet.MAX_TOTAL_BYTES) {
            errors.add("마이그레이션 합계가 " + MigrationSet.MAX_TOTAL_BYTES / 1024 + "KiB 를 넘는다");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("pgroll 마이그레이션 규칙 위반: " + String.join("; ", errors));
        }
        parsed.sort(Comparator.comparingLong(Migration::number));
        return new PgrollSet(List.copyOf(parsed));
    }

    public boolean isEmpty() {
        return migrations.isEmpty();
    }

    /** 번호 순 */
    public List<Migration> migrations() {
        return migrations;
    }

    public Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        migrations.forEach(m -> files.put(m.file(), m.body()));
        return files;
    }

    /** 앱이 접속할 스키마. pgroll 은 마이그레이션마다 이 이름으로 뷰를 만든다 */
    public static String versionSchema(String migrationName) {
        return "public_" + migrationName;
    }

    private static boolean pgrollFile(String file) {
        return file != null && (file.endsWith(".yaml") || file.endsWith(".yml") || file.endsWith(".json"));
    }

    /**
     * @param name pgroll 마이그레이션 이름 (파일명에서 확장자를 뺀 것)
     */
    public record Migration(long number, String name, String file, String body) {
    }
}
