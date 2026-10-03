# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 레이어를 먼저 받아 두면 코드만 바뀐 빌드는 여기부터 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN sh ./gradlew dependencies --no-daemon > /dev/null || true

COPY src ./src
RUN sh ./gradlew bootJar --no-daemon -x test

# ---------- pgroll (무중단 스키마 변경 CLI) ----------
FROM alpine:3.20 AS pgroll
ARG TARGETARCH=amd64
ARG PGROLL_VERSION=0.16.3
ARG PGROLL_SHA256_AMD64=e86ccd704f7d99a0794a75a6c1d1095d30c8bc8c21a22b793914db8340bad182
ARG PGROLL_SHA256_ARM64=c37f41f29b8e5784a47f91069975bd397448cf1014d47cd426680f18a84804a7
RUN set -eu; \
    if [ "$TARGETARCH" = "arm64" ]; then sum=$PGROLL_SHA256_ARM64; else sum=$PGROLL_SHA256_AMD64; fi; \
    wget -qO /pgroll "https://github.com/xataio/pgroll/releases/download/v${PGROLL_VERSION}/pgroll.linux.${TARGETARCH}"; \
    echo "$sum  /pgroll" | sha256sum -c -; \
    chmod +x /pgroll

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=pgroll /pgroll /usr/local/bin/pgroll
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app

EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
