# ==============================================================================
# 🧪 TESTS — integration tests module
# Recipes available as tests::<recipe>.
# ==============================================================================

[group('tests')]
[doc('Run integration tests')]
[working-directory(justfile_directory())]
common:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :tests:test

[group('tests')]
[doc('Run integration tests with verbose output')]
[working-directory(justfile_directory())]
verbose:
    #!/bin/bash
    set -euo pipefail
    ./gradlew :tests:test --info

[group('tests')]
[doc('Run full check: wbparser + tests + static gates (./check.sh)')]
[working-directory(justfile_directory())]
check:
    #!/bin/bash
    set -euo pipefail
    ./check.sh

[group('tests')]
[doc('Clean build artifacts')]
[working-directory(justfile_directory())]
clean:
    #!/bin/bash
    set -euo pipefail
    ./gradlew clean
