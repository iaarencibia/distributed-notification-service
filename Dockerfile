# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage
#
# The build runs inside the image so that a clean machine needs Docker and nothing
# else. Dependencies are resolved in their own layer, before the sources are copied,
# so editing code does not invalidate the dependency cache.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests are excluded from the image build: they need Testcontainers, which needs a
# Docker daemon. They run in CI and locally via `mvn verify`, not during packaging.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Unprivileged user: a compromised process should not own the filesystem.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app
EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container limit instead of
# from the host's memory, which is what it would otherwise see.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "/app/app.jar"]
