# ==============================================================================
# 🏗️ INFRASTRUCTURE — Ktor + Exposed + Flyway module
# Recipes available as infra::<recipe>.
# ==============================================================================

[group('infra')]
[doc('Run infrastructure module tests')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :infrastructure:test

[group('infra')]
[doc('Run infrastructure module tests with verbose output')]
[working-directory(justfile_directory())]
test-verbose:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :infrastructure:test --info

[group('lint')]
[doc('Run ktlintCheck for infrastructure module')]
[working-directory(justfile_directory())]
lint:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :infrastructure:ktlintCheck

[group('lint')]
[doc('Run detekt for infrastructure module')]
[working-directory(justfile_directory())]
detekt:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :infrastructure:detekt

[group('infra')]
[doc('Compile infrastructure module')]
[working-directory(justfile_directory())]
compile:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :infrastructure:compileKotlin
