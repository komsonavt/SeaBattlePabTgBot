FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN mkdir -p /app/config

# JAR собирается локально и поставляется вместе с исходниками: слабому VPS не
# требуется скачивать зависимости и компилировать Kotlin при каждом обновлении.
COPY build/libs/*.jar /app/seabattle.jar

ENTRYPOINT ["java", "-jar", "/app/seabattle.jar"]
