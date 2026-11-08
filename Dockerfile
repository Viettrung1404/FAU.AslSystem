# Build context: backend/ (the directory containing pom.xml).
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/main ./src/main
# Tests run separately; image builds require no database or runtime secrets.
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
COPY --from=build --chown=10001:10001 /build/target/backend-*.jar /app/app.jar

USER 10001:10001
# Spring reads Render's PORT environment variable (8080 locally by default).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
