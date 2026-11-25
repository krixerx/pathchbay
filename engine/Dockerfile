# One image for the engine, the admin API and the console (spec ADR-003). Build from engine/:
#   docker build -t patchbay .
#   docker run -p 8080:8080 -v mapper-config:/config patchbay
# Tests are not run here; run `mvn test` and `npm test` before building.

# 1. The console, built into src/main/resources/static (vite.config.ts outDir).
FROM node:24.19.0-alpine AS console
WORKDIR /build/console
COPY console/package.json console/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY console/ ./
RUN npm run build

# 2. The engine jar, with the console inside.
FROM maven:3.9.16-eclipse-temurin-21 AS engine
WORKDIR /build
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src/ src/
COPY --from=console /build/src/main/resources/static/ src/main/resources/static/
RUN mvn -q -B package -DskipTests

# 3. Runtime: a JRE, a non-root user, and /config as the only state (ADR-004, R4).
FROM eclipse-temurin:21.0.12_8-jre-noble
# The base image lags Ubuntu's security pocket, so every package is upgraded (apt CVE-2026-109518, brotli and openssl
# failed the Trivy image scan, 2026-11-25).
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app mapper \
    && mkdir -p /app /config \
    && chown mapper /config
WORKDIR /app
COPY --from=engine /build/target/engine-0.0.1-SNAPSHOT.jar app.jar
USER mapper
VOLUME /config
EXPOSE 8080
# Heap from the container's memory limit; on OutOfMemoryError stop, so the orchestrator restarts the engine instead
# of leaving it half-working. Override with -e JAVA_TOOL_OPTIONS=...
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Ready once every integration is loaded; open without INBOUND_API_KEY.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3     CMD curl -fs http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--mapper.config-dir=/config"]
