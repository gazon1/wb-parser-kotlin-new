# ==============================================================================
# 🚀 APP RUN
# ==============================================================================

[group('run')]
[doc('Run Spring Boot app locally (ADMIN_API_KEY/ADMIN_PASSWORD required)')]
[working-directory(justfile_directory())]
run:
    #!/bin/bash
    set -euo pipefail
    if [[ -n "{{SPRING_PROFILES_ACTIVE}}" ]]; then
        export SPRING_PROFILES_ACTIVE="{{SPRING_PROFILES_ACTIVE}}"
    fi
    # ADMIN_API_KEY defaults to dev key for local dev only
    export ADMIN_API_KEY="{{env_var_or_default("ADMIN_API_KEY", "dev-admin-key")}}"
    export ADMIN_PASSWORD="{{env_var_or_default("ADMIN_PASSWORD", "dev-password")}}"
    export SERVER_PORT="{{env_var_or_default("SERVER_PORT", "8080")}}"
    ./gradlew :app:bootRun --no-daemon

[group('docker')]
[doc('Run the local debug stack (app + postgres) via docker-compose.debug.yml')]
[working-directory(justfile_directory())]
run-docker:
    #!/bin/bash
    set -euo pipefail
    docker compose -f deploy/docker-compose.debug.yml up --build
