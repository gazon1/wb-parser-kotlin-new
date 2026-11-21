# ==============================================================================
# 🔬 RESEARCH — write a proposal from a validated investigation
#
# The generator reads a research record and writes the Kotlin proposal and the
# fixture-based regression tests that pin it. It writes files and nothing else:
# no commit, no branch, no merge — the diff is what a human reviews.
#
# Only a validated contract is eligible: a record whose claims are merely
# observed is refused, and the refusal says why.
# ==============================================================================

[group('research')]
[doc('Generate the Kotlin proposal and the regression tests of an investigation')]
[working-directory(justfile_directory())]
proposal investigation:
    #!/bin/bash
    set -euo pipefail
    uv run python scripts/wb-research/generate_proposal.py --investigation {{investigation}}
