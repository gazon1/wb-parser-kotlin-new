# ==============================================================================
# 🧹 LINT — the linters' own corrections
# Recipes available as lint::<recipe>.
#
# The same script the CI auto-fix workflow runs (scripts/ci/lint-autofix.sh):
# one implementation, two callers, so a correction that CI applies can also be
# reproduced locally.
# ==============================================================================

[group('lint')]
[doc('Apply the detekt and ktlint corrections to the working tree')]
[working-directory(justfile_directory())]
autofix:
    #!/bin/bash
    set -euo pipefail
    bash scripts/ci/lint-autofix.sh

[group('lint')]
[doc('Check the linters without changing anything')]
[working-directory(justfile_directory())]
check:
    #!/bin/bash
    set -euo pipefail
    ./gradlew ktlintCheck detekt
