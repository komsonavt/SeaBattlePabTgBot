# ===== Runtime-образ с готовым JAR =====
# JAR собирается ЛОКАЛЬНО (gradlew jar), а не на VPS.
# Это экономит ~18 минут на каждой сборке на слабом сервере.
#
# Перед сборкой образа выполните локально:
#   gradlew jar
# (или на Windows: gradlew.bat jar)
#
# Готовый JAR появится в build/libs/

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Копируем собранный fat-jar из локальной сборки
COPY build/libs/*.jar /app/seabattle.jar

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
