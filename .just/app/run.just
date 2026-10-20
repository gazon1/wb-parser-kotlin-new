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
    export SERVER_PORT="{{env_var_or_default("SERVER_PORT", "8071")}}"
    # DB defaults — docker-compose postgres exposed on 5433
    export DB_PORT="{{env_var_or_default("DB_PORT", "5433")}}"
    export DB_HOST="{{env_var_or_default("DB_HOST", "localhost")}}"
    export DB_NAME="{{env_var_or_default("DB_NAME", "wbparser")}}"
    export DB_USER="{{env_var_or_default("DB_USER", "wbparser")}}"
    export DB_PASSWORD="{{env_var_or_default("DB_PASSWORD", "wbparser")}}"
    ./gradlew :app:bootRun --no-daemon

[group('docker')]
[doc('Run the local debug stack (app + postgres) via docker-compose.debug.yml')]
[working-directory(justfile_directory())]
run-docker:
    #!/bin/bash
    set -euo pipefail
    # The debug image does NOT build the jar — it runs the one the host built, mounted
    # in as a file. `docker compose up --build` builds the *image*, not the jar, so
    # without this the container starts and dies with "Unable to access jarfile app.jar".
    just app::build bootJar
    docker compose -f deploy/docker-compose.debug.yml up --build

[group('docker')]
[doc('Rebuild the jar and recreate the debug container, so the stack runs the new code')]
[working-directory(justfile_directory())]
debug-rebuild:
    #!/bin/bash
    set -euo pipefail
    # The jar is mounted into the container as a *file*. A rebuilt jar therefore does
    # not reach a running container: the JVM holds the old file, and the container
    # keeps serving the code it started with. The container has to be recreated.
    #
    # This recipe exists because "I rebuilt and the behaviour did not change" was a
    # real trap: the old JVM on the host port and the debug container on another port
    # both looked like the application.
    just app::build bootJar
    docker compose -f deploy/docker-compose.debug.yml up -d --force-recreate app
    echo "✅ Debug app recreated — http://localhost:8082/api/health/ready"
