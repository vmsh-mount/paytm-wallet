# syntax=docker/dockerfile:1

# ---- build stage ------------------------------------------------------------
# JDK-only base; the pinned Maven wrapper fetches Maven 3.9.9 → one Maven
# version everywhere (wrapper, CI, image).
FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /build

# 1. dependency layer — only re-resolves when the POM or wrapper changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# 2. sources → jar (tests run in CI's `mvnw verify`, not here)
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests \
 && java -Djarmode=tools -jar target/wallet-*.jar extract --layers --launcher --destination /build/layers

# ---- runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699 AS runtime
WORKDIR /app

# non-root (brief requirement) — own /app so a read-only rootfs still works
RUN addgroup -S app && adduser -S -G app -h /app app

# ordered least- → most-volatile so an app-only change reuses the dep layers
COPY --from=build --chown=app:app /build/layers/dependencies/ ./
COPY --from=build --chown=app:app /build/layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/layers/application/ ./

USER app
EXPOSE 8080

# container-aware heap; no shell in the entrypoint
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# busybox wget is already in the alpine base — no extra package, no curl
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
  CMD wget -q -O - http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
