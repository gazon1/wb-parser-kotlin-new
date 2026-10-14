# ==============================================================================
# 🚀 APP BUILD
# ==============================================================================

[group('build')]
[doc('Build app JAR: VARIANT=bootJar|compileKotlin (default bootJar)')]
[working-directory(justfile_directory())]
build VARIANT="bootJar":
    #!/bin/bash
    set -euo pipefail
    case "{{VARIANT}}" in
        bootJar)
            ./gradlew :app:bootJar --no-daemon
            echo "✅ app/build/libs/ (run 'ls app/build/libs/' to find the JAR)"
            ;;
        compileKotlin)
            ./gradlew :app:compileKotlin --no-daemon
            echo "✅ app:compileKotlin passed"
            ;;
        *)
            echo "❌ Unknown variant: {{VARIANT}} (use bootJar | compileKotlin)"
            exit 1
            ;;
    esac

[group('build')]
[doc('Build bootJar and show output file')]
[private]
[working-directory(justfile_directory())]
boot-jar:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :app:bootJar --no-daemon
    echo "✅ app:bootJar done"

[group('docker')]
[doc('Build the production image via Dockerfile.app (TAG=<sha> names it)')]
[working-directory(justfile_directory())]
docker-build tag="local":
    #!/bin/bash
    set -euo pipefail
    # No --build-arg JAR_FILE here: Dockerfile.app has no such ARG and has never
    # had one. It compiles from source in the builder stage, because a prebuilt
    # JAR cannot be COPYed in — .dockerignore excludes build outputs on purpose,
    # so a build context carrying a stale jar produced an image whose contents
    # nobody could account for.
    docker build \
        -f Dockerfile.app \
        -t "wb-parser:{{ tag }}" \
        .
    echo "✅ Docker image built: wb-parser:{{ tag }}"
