package com.lily.cicd.schema;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pgroll CLI 를 프로젝트 계정으로 실행한다. 이미지에 바이너리가 들어 있다 (Dockerfile).
 *
 * <p>비밀번호는 URL 이 아니라 {@code PGPASSWORD} 로 넘겨서 프로세스 인자와 로그에 남지 않게 한다.
 * 출력의 진행 표시(ANSI, \r)는 지우고, 실패하면 마지막 줄을 이유로 쓴다.
 */
public class PgrollCli {

    private static final Pattern JDBC = Pattern.compile("^jdbc:postgresql://([^/?]+)/([^?]+)");

    private final Settings settings;

    public PgrollCli(Settings settings) {
        this.settings = settings;
    }

    /** 마이그레이션 하나를 시작한다. complete 면 바로 끝까지 (이전 버전 스키마를 지운다) */
    public String start(DbTarget target, Path file, boolean complete) {
        List<String> args = new ArrayList<>(List.of("start", file.toString(),
                "--backfill-batch-size", Integer.toString(settings.backfillBatchSize()),
                "--backfill-batch-delay", settings.backfillBatchDelay()));
        if (complete) {
            args.add("--complete");
        }
        return run(target, args);
    }

    public String complete(DbTarget target) {
        return run(target, List.of("complete"));
    }

    public String rollback(DbTarget target) {
        return run(target, List.of("rollback"));
    }

    /** 이력 없이 테이블만 있는 DB (Flyway 로 관리하던 앱) 의 현재 스키마를 출발점으로 기록한다 */
    public String baseline(DbTarget target, String name, Path dir) {
        return run(target, List.of("baseline", name, dir.toString(), "--yes"));
    }

    String run(DbTarget target, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(settings.binary());
        command.addAll(args);
        command.add("--postgres-url");
        command.add(url(target, settings.sslmode()));
        command.add("--lock-timeout");
        command.add(Integer.toString(settings.lockTimeoutMillis()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("PGPASSWORD", target.password());
        env.put("NO_COLOR", "1");
        String verb = args.get(0);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SchemaOperationException("pgroll 실행 실패: " + e.getMessage(), e);
        }
        try (InputStream out = process.getInputStream()) {
            // 출력이 파이프 버퍼를 채우면 프로세스가 멈추므로 끝날 때까지 읽는다. 시간 초과는 별도 스레드가 끊는다
            Thread watchdog = Thread.ofVirtual().start(() -> {
                try {
                    if (!process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            String output = clean(new String(out.readAllBytes(), StandardCharsets.UTF_8));
            int exit = process.waitFor();
            watchdog.interrupt();
            if (exit != 0) {
                throw new SchemaOperationException("pgroll " + verb + " 실패: " + lastLine(output), null);
            }
            return lastLine(output);
        } catch (IOException e) {
            throw new SchemaOperationException("pgroll 출력 읽기 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SchemaOperationException("pgroll " + verb + " 대기 중 중단됨", e);
        }
    }

    /** {@code jdbc:postgresql://host:port/db} → {@code postgres://user@host:port/db?sslmode=..}. 비밀번호는 넣지 않는다 */
    static String url(DbTarget target, String sslmode) {
        Matcher m = JDBC.matcher(target.url());
        if (!m.find()) {
            throw new IllegalStateException("pgroll 은 postgres 만 지원한다");
        }
        return "postgres://" + URLEncoder.encode(target.username(), StandardCharsets.UTF_8)
                + "@" + m.group(1) + "/" + m.group(2) + "?sslmode=" + sslmode;
    }

    static String clean(String output) {
        return output.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replace('\r', '\n').trim();
    }

    static String lastLine(String output) {
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim().replaceAll("\\s{2,}", " ");
            }
        }
        return "(출력 없음)";
    }

    /**
     * @param sslmode            lib/pq 값. RDS 는 require
     * @param lockTimeoutMillis  DDL 이 잠금을 기다리는 최대 시간. 넘으면 실패해서 서비스 쿼리를 막지 않는다
     * @param timeoutSeconds     명령 하나의 최대 시간 (backfill 포함)
     */
    public record Settings(String binary, String sslmode, int lockTimeoutMillis, int timeoutSeconds,
                           int backfillBatchSize, String backfillBatchDelay) {

        public static Settings defaults() {
            return new Settings("pgroll", "require", 500, 300, 1000, "0s");
        }
    }
}
