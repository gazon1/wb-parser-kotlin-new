# ==============================================================================
# 🧩 BROWSER — the local Camoufox runtime and the profile pool
# Recipes available as browser::<recipe>.
# ==============================================================================

[group('browser')]
[doc('Install the pinned Camoufox build')]
[working-directory(justfile_directory())]
install:
    #!/usr/bin/env bash
    set -euo pipefail
    ./gradlew :browser:camoufoxInstall

[group('browser')]
[doc('Verify that the pinned Camoufox build is installed')]
[working-directory(justfile_directory())]
verify:
    #!/usr/bin/env bash
    set -euo pipefail
    ./gradlew :browser:camoufoxVerify

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
