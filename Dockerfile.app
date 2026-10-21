# syntax=docker/dockerfile:1
#
# Two stages on purpose.
#
# The builder needs a JDK, not a JRE: Kotlin compilation invokes javac through
# Gradle, and `eclipse-temurin:22-jre-alpine` has no compiler at all, so the
# build died with "No Java compiler found, please ensure you are running Gradle
# with a JDK" — which reads like a misconfiguration rather than a wrong base
# image for the stage.
#
# The runtime stage stays on the JRE: the application only needs to run the jar,
# and not shipping a compiler is most of why it is smaller.

# ── builder ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

# Gradle writes outside /app and the build is not reproducible byte-for-byte
# otherwise.
ENV GRADLE_USER_HOME=/root/.gradle

# The wrapper first, so a source-only change does not invalidate the layer that
# unpacks the distribution.
COPY gradlew ./
COPY gradle ./gradle

# Cache mount, not a layer: the dependency cache must not ship in the image, but
# re-downloading Kotlin, Spring, Ktor and Arrow on every build costs more than
# the layer it would save. `--mount=type=cache` gives the fast path without the
# fat image.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --version

# Resolve the external dependencies before the sources are copied. The build
# files are what the resolution depends on, so copying them first means a
# change to a source file leaves this layer intact: Kotlin, Spring, Ktor and
# Arrow are fetched once instead of on every rebuild.
COPY settings.gradle.kts build.gradle.kts ./
COPY wbparser/build.gradle.kts wbparser/
COPY app/build.gradle.kts app/
COPY tests/build.gradle.kts tests/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:resolveExternalDependenciesMain

COPY . .

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:bootJar -x test -x ktlintCheck -x detekt

# ── runtime ──────────────────────────────────────────────────────────────────
# NOTE: eclipse-temurin:22-jre (Debian) not Alpine — Playwright Java bundles a
# Node.js binary linked against glibc/libstdc++, which Alpine's musl libc does
# not provide. All deps below satisfy both the Node.js runtime and the Chromium
# browser binary that Playwright downloads.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libstdc++6 \
    libgcc-s1 \
    libglib2.0-0 \
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libpango-1.0-0 \
    libcairo2 \
    libasound2t64 \
    libpangocairo-1.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Non-root: a crawler reaching out to the internet under a shell account has no
# business owning the jar it runs.
RUN groupadd -g 1001 crawler && useradd -u 1001 -g crawler crawler

COPY --from=builder --chown=crawler:crawler /app/app/build/libs/app.jar app.jar

USER crawler
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]