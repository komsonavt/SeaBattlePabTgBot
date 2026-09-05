FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /build

# Docker использует этот слой повторно, пока зависимости проекта не менялись.
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon jar

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN mkdir -p /app/config
COPY --from=builder /build/build/libs/*.jar /app/seabattle.jar

ENTRYPOINT ["java", "-jar", "/app/seabattle.jar"]
