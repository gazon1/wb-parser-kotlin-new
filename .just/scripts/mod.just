# ==============================================================================
# 📜 SCRIPTS — refresh-decisions, bench
# Wrappers around existing project scripts in ./scripts/
# ==============================================================================

[group('scripts')]
[doc('Rebuild docs/decisions/DIGEST.md from dated entries')]
[working-directory(justfile_directory())]
refresh-decisions:
    #!/bin/bash
    set -euo pipefail
    ./scripts/refresh-decisions-digest.sh
