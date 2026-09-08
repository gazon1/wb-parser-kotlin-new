# ==============================================================================
# 🧩 DOMAIN — pure-Kotlin module, no framework deps
# Recipes available as domain::<recipe>.
# ==============================================================================

[group('domain')]
[doc('Run domain module tests')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :domain:test

[group('domain')]
[doc('Run domain module tests with verbose output')]
[working-directory(justfile_directory())]
test-verbose:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :domain:test --info

[group('lint')]
[doc('Run ktlintCheck for domain module')]
[working-directory(justfile_directory())]
lint:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :domain:ktlintCheck

[group('lint')]
[doc('Run detekt for domain module')]
[working-directory(justfile_directory())]
detekt:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :domain:detekt
