# syntax=docker/dockerfile:1.7

ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk AS builder

WORKDIR /workspace/backend
COPY backend/ ./

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew :deployments:monolith:bootJar --no-daemon

FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

ARG APP_VERSION=local
ARG DEBIAN_FRONTEND=noninteractive

LABEL org.opencontainers.image.title="archone-monolith" \
      org.opencontainers.image.description="Archone modular monolith deployment" \
      org.opencontainers.image.version="${APP_VERSION}"

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 archone \
    && useradd --uid 10001 --gid archone --home-dir /app --create-home archone

WORKDIR /app
COPY --from=builder --chown=archone:archone \
    /workspace/backend/deployments/monolith/build/libs/archone-monolith.jar \
    /app/archone-monolith.jar

USER archone

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl --fail --silent --show-error http://localhost:8081/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/archone-monolith.jar"]
