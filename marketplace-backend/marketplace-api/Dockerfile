# Dockerfile — place in marketplace-backend/marketplace-api/
# Multi-stage: build with a full JDK, run on a JRE. Tests do NOT run here —
# CI's test job is the gate; the image build assumes green.

# ---------- Stage 1: build ----------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Dependency layer first: as long as pom.xml is unchanged, Docker reuses
# this layer and rebuilds skip the download entirely. go-offline is
# best-effort (some plugins resolve lazily) but catches ~95%.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN ./mvnw --no-transfer-progress dependency:go-offline -q || true

COPY src src
RUN ./mvnw --no-transfer-progress package -DskipTests -q

# Spring Boot layered-jar extraction: splits the fat jar into
# dependencies / spring-boot-loader / snapshot-dependencies / application.
# Your code changes daily; your dependencies change monthly — after this,
# a code-only change rebuilds a layer of a few hundred KB instead of
# re-shipping an 80MB fat jar every deploy.
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-jammy

# Non-root: a container compromise shouldn't hand out root, and platforms
# increasingly refuse root containers outright.
RUN groupadd --system app && useradd --system --gid app app
USER app
WORKDIR /app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# Railway injects PORT; server.port=${PORT:8080} in application.yml picks
# it up (see integration notes — that one line is required or Railway's
# health checks hit the wrong port and the deploy loops forever).
EXPOSE 8080

# JVM sizing: respect container memory limits (default-on in 21 but
# MaxRAMPercentage tunes the share; 75% leaves headroom for metaspace
# and native memory so the container isn't OOM-killed at exactly the
# moment the heap thinks it has room).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD ["java", "-version"]
# ^ placeholder-grade healthcheck (proves the JVM is alive). Upgrade to a
# real one once curl is in the image: replace CMD with
#   CMD curl -fsS http://localhost:${PORT:-8080}/actuator/health || exit 1
# That requires apt-get install curl in the runtime stage — see integration
# notes for the trade-off vs Railway's own HTTP healthcheck.

# Boot 3.2+ loader class: org.springframework.boot.loader.launch.JarLauncher
# The pre-3.2 path (org.springframework.boot.loader.JarLauncher) is gone —
# using the old one produces ClassNotFoundException at container start.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
