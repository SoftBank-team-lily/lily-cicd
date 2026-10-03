package com.lily.cicd.config;

import com.lily.cicd.schema.PgrollCli;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * pgroll 무중단 스키마 변경.
 *
 * @param sslmode                lib/pq 값. RDS 는 require, 로컬 Postgres 컨테이너는 disable
 * @param lockTimeoutMillis      DDL 이 잠금을 기다리는 최대 시간. 넘으면 배포가 실패하고 서비스 쿼리는 막지 않는다
 * @param timeoutSeconds         pgroll 명령 하나의 최대 시간 (backfill 포함)
 * @param rollbackWindowSeconds  전환 뒤 complete 하기 전까지. 이 동안은 데이터 손실 없이 스키마까지 되돌린다
 * @param completerIntervalSeconds 롤백 창이 지난 마이그레이션을 찾는 주기
 */
@ConfigurationProperties(prefix = "lily.schema.pgroll")
public record PgrollProperties(
        @DefaultValue("pgroll") String binary,
        @DefaultValue("require") String sslmode,
        @DefaultValue("500") int lockTimeoutMillis,
        @DefaultValue("300") int timeoutSeconds,
        @DefaultValue("1000") int backfillBatchSize,
        @DefaultValue("0s") String backfillBatchDelay,
        @DefaultValue("600") int rollbackWindowSeconds,
        @DefaultValue("30") int completerIntervalSeconds) {

    public PgrollCli.Settings cli() {
        return new PgrollCli.Settings(binary, sslmode, lockTimeoutMillis, timeoutSeconds,
                backfillBatchSize, backfillBatchDelay);
    }
}
