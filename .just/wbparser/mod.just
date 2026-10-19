# ==============================================================================
# 🧩 WBPARSER — the crawler module (domain + infrastructure merged)
# Recipes available as wbparser::<recipe>.
# ==============================================================================

[group('wbparser')]
[doc('Run the crawler module unit tests')]
[working-directory(justfile_directory())]
test:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :wbparser:test

[group('wbparser')]
[doc('Run the crawler module unit tests with verbose output')]
[working-directory(justfile_directory())]
test-verbose:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :wbparser:test --info

[group('wbparser')]
[doc('Lint the crawler module (ktlint + detekt)')]
[working-directory(justfile_directory())]
lint:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :wbparser:ktlintCheck :wbparser:detekt

[group('wbparser')]
[doc('Run the coverage gate for the crawler module')]
[working-directory(justfile_directory())]
coverage:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :wbparser:koverVerify
