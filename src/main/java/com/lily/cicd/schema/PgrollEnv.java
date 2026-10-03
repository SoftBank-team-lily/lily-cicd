package com.lily.cicd.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 앱이 pgroll 버전 스키마로 접속하도록 DB 접속 환경변수를 고친다.
 *
 * <p>pgroll 트리거는 {@code search_path} 가 최신 버전 스키마 이름과 정확히 같을 때만 새 버전의 쓰기로 본다.
 * 그래서 다른 스키마를 뒤에 붙이지 않고 버전 스키마 하나만 넣는다.
 */
public final class PgrollEnv {

    /** 앱이 직접 search_path 를 정할 때 읽는 값 */
    public static final String SCHEMA_ENV = "LILY_DB_SCHEMA";
    private static final String[] JDBC_KEYS = {"DB_URL", "SPRING_DATASOURCE_URL"};
    private static final String URI_KEY = "DATABASE_URL";

    private PgrollEnv() {
    }

    public static Map<String, String> withSchema(Map<String, String> databaseEnv, String schema) {
        Map<String, String> env = new LinkedHashMap<>(databaseEnv);
        for (String key : JDBC_KEYS) {
            String url = env.get(key);
            if (url != null && !url.isBlank()) {
                env.put(key, param(url, "currentSchema", schema));
            }
        }
        String uri = env.get(URI_KEY);
        if (uri != null && !uri.isBlank()) {
            // libpq·node-postgres·psycopg 가 읽는 접속 옵션
            env.put(URI_KEY, param(uri, "options", "-c%20search_path%3D" + schema));
        }
        env.put(SCHEMA_ENV, schema);
        return env;
    }

    /** 같은 이름의 파라미터가 있으면 바꾸고, 없으면 붙인다 */
    static String param(String url, String name, String value) {
        int q = url.indexOf('?');
        if (q < 0) {
            return url + "?" + name + "=" + value;
        }
        StringBuilder out = new StringBuilder(url.substring(0, q + 1));
        boolean replaced = false;
        boolean first = true;
        for (String part : url.substring(q + 1).split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!first) {
                out.append('&');
            }
            first = false;
            if (part.startsWith(name + "=")) {
                out.append(name).append('=').append(value);
                replaced = true;
            } else {
                out.append(part);
            }
        }
        if (!replaced) {
            out.append(first ? "" : "&").append(name).append('=').append(value);
        }
        return out.toString();
    }
}
