# syntax=docker/dockerfile:1.7

FROM node:24-bookworm-slim AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY config ./config
COPY dependency-check-suppressions.xml .
COPY src ./src
RUN rm -rf src/main/resources/static/*.html src/main/resources/static/css src/main/resources/static/js
COPY --from=frontend-build /workspace/frontend/dist/ ./src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /workspace
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        fontconfig \
        libfreetype6 \
        fonts-dejavu \
    && apt-get install -y --only-upgrade \
        libssl3 \
        openssl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=extract --chown=app:app /workspace/app.jar ./app.jar
COPY --from=extract --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /workspace/extracted/application/ ./

RUN mkdir -p /tmp /app/logs /data/images \
    && chown -R app:app /app /tmp /data/images

USER app
EXPOSE 8888
VOLUME ["/tmp", "/app/logs", "/data/images"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8888/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.io.tmpdir=/tmp -jar app.jar"]
