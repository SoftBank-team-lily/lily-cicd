# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 레이어를 먼저 받아 두면 코드만 바뀐 빌드는 여기부터 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN sh ./gradlew dependencies --no-daemon > /dev/null || true

COPY src ./src
RUN sh ./gradlew bootJar --no-daemon -x test

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app

EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
