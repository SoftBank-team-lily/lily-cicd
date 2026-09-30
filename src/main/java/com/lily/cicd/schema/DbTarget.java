package com.lily.cicd.schema;

import java.util.Map;

/**
 * 사용자 앱 DB 접속 정보. lily-db-provisioner 가 앱에 넣는 환경변수에서 읽는다.
 *
 * @param url JDBC URL. 예: {@code jdbc:postgresql://host:5432/blog}
 */
public record DbTarget(String url, String username, String password) {

    public static DbTarget from(Map<String, String> env) {
        String url = first(env, "DB_URL", "SPRING_DATASOURCE_URL");
        String username = first(env, "DB_USERNAME", "SPRING_DATASOURCE_USERNAME");
        String password = first(env, "DB_PASSWORD", "SPRING_DATASOURCE_PASSWORD");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("DB 접속 정보(DB_URL, DB_USERNAME, DB_PASSWORD)가 없다");
        }
        if (!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException("postgres 와 mysql 만 지원한다: " + url.replaceAll("//.*", "//..."));
        }
        return new DbTarget(url, username, password);
    }

    public boolean postgres() {
        return url.startsWith("jdbc:postgresql:");
    }

    /** 비밀번호는 로그에 남기지 않는다 */
    @Override
    public String toString() {
        return "DbTarget[" + url + ", " + username + "]";
    }

    private static String first(Map<String, String> env, String... keys) {
        if (env == null) {
            return null;
        }
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
