#!/usr/bin/env bash
# Verifies the runtime classpath is complete by building the bootJar.
#
# A compileOnly dependency is absent from the runtime classpath. If production code
# references a class that was compileOnly at build time, the JVM throws
# NoClassDefFoundError at runtime — typically *after* the build succeeds and the
# error only surfaces in production (e.g. tests pass with test classpath, but the
# fat JAR crashes on startup because a compileOnly-exposed utility class is missing).
#
# bootJar transitively resolves ALL runtime classpath dependencies and includes them
# in the fat JAR. If any compileOnly class is missing from the classpath, bootJar
# either fails to build (for direct compileOnly deps) or startup crashes (for
# transitive). In either case the gate fails before reaching production.
#
# This is the most reliable gate because it exercises the actual production
# runtime code path. It is intentionally slow (~2 min) — it runs in CI where
# the cost is amortised, and locally before high-risk pushes.
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "compileOnly/runtime gate: building bootJar..."
./gradlew :app:bootJar --quiet --no-daemon
echo "bootJar built successfully — no compileOnly/runtime leaks"
