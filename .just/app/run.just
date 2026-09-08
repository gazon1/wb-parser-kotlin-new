# ==============================================================================
# 🚀 APP RUN
# ==============================================================================

[group('run')]
[doc('Run Spring Boot app locally (respects $SPRING_PROFILES, default: local)')]
[working-directory(justfile_directory())]
run:
    #!/bin/bash
    set -euo pipefail
    SPRING_PROFILES="{{SPRING_PROFILES}}" ./gradlew :app:bootRun --no-daemon

[group('docker')]
[doc('Run app via docker-compose')]
[working-directory(justfile_directory())]
run-docker:
    #!/bin/bash
    set -euo pipefail
    docker-compose up --build app
