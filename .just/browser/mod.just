# ==============================================================================
# 🧩 BROWSER — the browser stack (AdsPower client + Playwright overlay + pool)
# Recipes available as browser::<recipe>.
# ==============================================================================

[group('browser')]
[doc('Run the browser module unit tests')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser:test

[group('browser')]
[doc('Run the browser module unit tests with verbose output')]
[working-directory(justfile_directory())]
test-verbose:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser:test --info

[group('browser')]
[doc('Lint the browser module (ktlint + detekt)')]
[working-directory(justfile_directory())]
lint:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser:ktlintCheck :browser:detekt

[group('browser')]
[doc('Run the coverage gate for the browser module')]
[working-directory(justfile_directory())]
coverage:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser:koverVerify
