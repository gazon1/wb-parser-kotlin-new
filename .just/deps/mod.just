# ==============================================================================
# 🔍 DEPS — dependency analysis (DAGP)
# The dependency-analysis plugin compares what the build declares against what
# the bytecode uses. The accepted-findings list lives in build.gradle.kts and
# may only shrink — see docs/decisions/2026-10-19-dependency-analysis-plugin.md.
# ==============================================================================

[group('deps')]
[doc('Run the dependency analysis and fail on findings outside the accepted list')]
[working-directory(justfile_directory())]
health:
    #!/bin/bash
    set -euo pipefail
    ./gradlew buildHealth

[group('deps')]
[doc('Print the dependency health report')]
[working-directory(justfile_directory())]
report:
    #!/bin/bash
    set -euo pipefail
    ./gradlew buildHealth --quiet
    cat build/reports/dependency-analysis/build-health-report.txt

[group('deps')]
[doc('Let the plugin rewrite the build scripts (MANUAL ONLY — review the diff)')]
[working-directory(justfile_directory())]
fix:
    #!/bin/bash
    set -euo pipefail
    echo "fixDependencies rewrites build scripts. It cannot see runtime-only"
    echo "dependencies and may remove one. Review the diff and run ./check.sh."
    ./gradlew fixDependencies

[group('deps')]
[doc('Render the project dependency graph')]
[working-directory(justfile_directory())]
graph:
    #!/bin/bash
    set -euo pipefail
    ./gradlew projectGraphMain
