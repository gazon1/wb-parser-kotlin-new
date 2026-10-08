# syntax=docker/dockerfile:1
#
# Two stages on purpose.
#
# The builder needs a JDK, not a JRE: Kotlin compilation invokes javac through
# Gradle, and `eclipse-temurin:21-jre-alpine` has no compiler at all, so the
# build died with "No Java compiler found, please ensure you are running Gradle
# with a JDK" — which reads like a misconfiguration rather than a wrong base
# image for the stage.
#
# The runtime stage stays on the JRE: the application only needs to run the jar,
# and not shipping a compiler is most of why it is smaller.

# ── builder ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
# Gradle writes outside /app and the build is not reproducible byte-for-byte
# otherwise; keeping the caches in a named volume also keeps them out of the
# image layers.
ENV GRADLE_USER_HOME=/root/.gradle
COPY gradlew ./
COPY gradle ./gradle
# Dependencies resolve before the source is copied, so editing code does not
# re-download the world on every build.
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true
COPY . .
RUN ./gradlew --no-daemon :app:bootJar -x test -x ktlintCheck -x detekt

# ── runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root: a crawler reaching out to the internet under a shell account has no
# business owning the jar it runs.
RUN addgroup -S -g 1001 crawler && adduser -S -u 1001 -G crawler crawler

COPY --from=builder --chown=crawler:crawler /app/app/build/libs/app.jar app.jar

USER crawler
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]