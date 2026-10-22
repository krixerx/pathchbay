# One image for the engine, the admin API and the console (spec ADR-003). Build from engine/:
#   docker build -t integration-mapper .
#   docker run -p 8080:8080 -v mapper-config:/config integration-mapper
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
RUN useradd --system --uid 10001 --home-dir /app mapper \
    && mkdir -p /app /config \
    && chown mapper /config
WORKDIR /app
COPY --from=engine /build/target/engine-0.0.1-SNAPSHOT.jar app.jar
USER mapper
VOLUME /config
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--mapper.config-dir=/config"]
