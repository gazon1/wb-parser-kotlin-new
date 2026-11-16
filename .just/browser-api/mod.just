# ==============================================================================
# 🧩 BROWSER-API — the browser contract (page, session, profile, pool)
# Recipes available as browser-api::<recipe>.
# ==============================================================================

[group('browser-api')]
[doc('Run the browser contract module unit tests')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser-api:test

[group('browser-api')]
[doc('Run the browser contract module unit tests with verbose output')]
[working-directory(justfile_directory())]
test-verbose:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser-api:test --info

[group('browser-api')]
[doc('Lint the browser contract module (ktlint + detekt)')]
[working-directory(justfile_directory())]
lint:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser-api:ktlintCheck :browser-api:detekt

[group('browser-api')]
[doc('Run the coverage gate for the browser contract module')]
[working-directory(justfile_directory())]
coverage:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :browser-api:koverVerify
