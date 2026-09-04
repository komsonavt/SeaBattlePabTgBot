# ===== Стадия 1: сборка fat-jar через Gradle =====
# Используем Alpine-образ — значительно меньше Debian-варианта
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Копируем файлы сборки Gradle (для кэширования слоя зависимостей)
COPY gradle/ ./gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./

# Загружаем зависимости (кэшируется отдельно от исходников)
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Копируем исходники
COPY src/ ./src/

# Собираем fat-jar (без тестов)
RUN ./gradlew --no-daemon jar -x test --no-build-cache

# ===== Стадия 2: runtime на лёгком JRE Alpine =====
# Alpine-образ JRE ~120 МБ вместо ~270 МБ у Debian-варианта
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Копируем собранный fat-jar
COPY --from=builder /build/build/libs/*.jar /app/seabattle.jar

# Переменные окружения (переопределяются при запуске)
ENV BOT_TOKEN=""
ENV BOT_USERNAME=""
ENV CORPORATE_CHAT_ID=""
ENV ADMIN_IDS=""
ENV GROUP_SIZE="4"
ENV PLAYERS_PER_GROUP_ADVANCE="2"
ENV DB_URL="jdbc:postgresql://db:5432/seabattle"
ENV DB_USER="seabattle"
ENV DB_PASSWORD="seabattle"
ENV TURN_TIMEOUT_SECONDS="300"

# Запуск бота
ENTRYPOINT ["java", "-jar", "/app/seabattle.jar"]
