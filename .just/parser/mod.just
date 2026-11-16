# ==============================================================================
# 🕹️ PARSER — operate the local crawler from a terminal
#
# One entry point: scripts/parser/wbparser.py. There is no web UI in this
# repository — the catalog is a read API with its own storefront elsewhere, and
# `items` reads it through the same endpoints the storefront does.
#
# The script's dependencies live in `pyproject.toml`, so the only setup step is
# `uv sync` — every recipe below runs it through `uv run`.
# ==============================================================================

[group('parser')]
[doc('Start the stack (Postgres + the application) and wait until it can serve')]
[working-directory(justfile_directory())]
up *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py up {{ARGS}}

[group('parser')]
[doc('Stop the stack (the database volume is kept)')]
[working-directory(justfile_directory())]
down *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py down {{ARGS}}

[group('parser')]
[doc('Health, readiness, credentials, crawl counters, egress groups')]
[working-directory(justfile_directory())]
status *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py status {{ARGS}}

[group('parser')]
[doc('What was scraped, paged and filtered (the storefront own data)')]
[working-directory(justfile_directory())]
items *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py items {{ARGS}}

[group('parser')]
[doc('The journal of runs: which target ran, from which address, how it went')]
[working-directory(justfile_directory())]
runs *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py runs {{ARGS}}

[group('parser')]
[doc('The tick, the freshness window, and which targets are due')]
[working-directory(justfile_directory())]
schedule *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py schedule {{ARGS}}

[group('parser')]
[doc('Aggregate counters of the catalog')]
[working-directory(justfile_directory())]
stats *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py stats {{ARGS}}

[group('parser')]
[doc('Run exactly one crawl in the foreground (profile: once)')]
[working-directory(justfile_directory())]
crawl *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py crawl {{ARGS}}

[group('parser')]
[doc('Tail or follow the application log')]
[working-directory(justfile_directory())]
logs *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py logs {{ARGS}}

[group('parser')]
[doc('Last crawler runs, what started each, and when the next one is due')]
[working-directory(justfile_directory())]
history *ARGS:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py history {{ARGS}}

[group('parser')]
[doc('Show the help of every command')]
[working-directory(justfile_directory())]
help:
    #!/usr/bin/env bash
    set -euo pipefail
    uv run scripts/parser/wbparser.py help
