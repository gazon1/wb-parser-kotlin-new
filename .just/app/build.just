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
[doc('Build Docker image via Dockerfile.app')]
[working-directory(justfile_directory())]
docker-build:
    #!/bin/bash
    set -euo pipefail
    docker build \
        -f Dockerfile.app \
        --build-arg JAR_FILE=app/build/libs/*.jar \
        -t wb-parser:latest \
        .
    echo "✅ Docker image built: wb-parser:latest"
