# ==============================================================================
# 🚀 APP RUN
# ==============================================================================

[group('run')]
[doc('Run Spring Boot app locally (respects $SPRING_PROFILES, default: local)')]
[working-directory(justfile_directory())]
run:
    #!/bin/bash
    set -euo pipefail
    if [[ -n "{{SPRING_PROFILES}}" ]]; then
        export SPRING_PROFILES="{{SPRING_PROFILES}}"
    fi
    ./gradlew :app:bootRun --no-daemon

[group('docker')]
[doc('Run the local debug stack (app + postgres) via docker-compose.debug.yml')]
[working-directory(justfile_directory())]
run-docker:
    #!/bin/bash
    set -euo pipefail
    docker compose -f deploy/docker-compose.debug.yml up --build
