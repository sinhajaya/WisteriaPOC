# ── Wisteria API · Spring Boot 4.1 (Java 21) ─────────────────
# Multi-stage: build the fat jar with Maven, run it on a slim JRE.
# Single-module project — build context is wisteria-api/.

# Stage 1 — build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so they cache when only src/ changes.
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
RUN mvn -q -e -B clean package -DskipTests

# Stage 2 — run
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl is used by the compose healthcheck against /api/v1/style/health
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
