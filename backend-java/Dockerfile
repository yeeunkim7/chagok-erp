# syntax=docker/dockerfile:1
ARG JDK_IMAGE=eclipse-temurin:21-jdk-jammy@sha256:432e54724c3e57d1846aeb886f4d339f3b0522931c4e64119c2797013b7cfc3c
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c

FROM ${JDK_IMAGE} AS build-base
ENV GRADLE_USER_HOME=/home/app/.gradle
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app \
    && command -v setpriv && mkdir /workspace && chown app:app /workspace
WORKDIR /workspace
COPY --chown=app:app gradlew build.gradle settings.gradle ./
COPY --chown=app:app gradle ./gradle
COPY --from=docker --chmod=0755 /gradle-network.sh /opt/docker/gradle-network.sh
COPY --from=docker --chmod=0644 /resolve-test-dependencies.gradle /opt/docker/resolve-test-dependencies.gradle
RUN chmod +x gradlew
USER app

FROM build-base AS build
COPY --chown=app:app src/main ./src/main
RUN --mount=type=secret,id=proxy_ca,uid=10001,required=false \
    /opt/docker/gradle-network.sh bootJar
COPY --from=docker --chmod=0644 /HealthProbe.java /tmp/HealthProbe.java
RUN javac -d /tmp/health /tmp/HealthProbe.java

# Development-only image: wrapper distribution and resolved test JARs are retained
# in this layer so execution never needs network dependency downloads.
FROM build-base AS test-runner
COPY --chown=app:app src ./src
RUN --mount=type=secret,id=proxy_ca,uid=10001,required=false \
    /opt/docker/gradle-network.sh -I /opt/docker/resolve-test-dependencies.gradle \
    compileTestJava resolveDockerTestDependencies
COPY --from=docker --chmod=0755 /test-entrypoint.sh /opt/docker/test-entrypoint.sh
ENTRYPOINT ["/opt/docker/test-entrypoint.sh"]
CMD ["test"]

FROM ${JRE_IMAGE} AS runtime
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app \
    && command -v setpriv
WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
COPY --from=build /tmp/health /opt/health
COPY --from=docker --chmod=0755 /backend-entrypoint.sh /opt/docker/backend-entrypoint.sh
ENV AI_API_KEY="" APP_AI_ENABLED=false
USER app
EXPOSE 8080
# Liveness only. PostgreSQL health and application DB access are checked separately.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["java", "-cp", "/opt/health", "HealthProbe"]
ENTRYPOINT ["/opt/docker/backend-entrypoint.sh"]
CMD ["java", "-jar", "/app/app.jar"]
