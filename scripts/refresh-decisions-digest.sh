#!/usr/bin/env bash
# refresh-decisions-digest.sh — rebuild docs/decisions/DIGEST.md from the
# dated entries in docs/decisions/*.md.
#
# Output sections:
#   1. Critical — auto-extracted from **Always**/**Never**/**MUST** markers
#      in Consequences (rules the agent MUST NOT violate).
#   2. Per-tag — remaining Consequences bullets, grouped by their entry's
#      `tags:` frontmatter.
#   3. Index — slug → tags mapping for grep / mdq.
#
# Idempotent. Safe to run any time. Run before any non-trivial agent task.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DECISIONS_DIR="$PROJECT_ROOT/docs/decisions"
DIGEST="$DECISIONS_DIR/DIGEST.md"

if [[ ! -d "$DECISIONS_DIR" ]]; then
    echo "error: $DECISIONS_DIR does not exist" >&2
    exit 1
fi

mapfile -t ENTRIES < <(
    find "$DECISIONS_DIR" -mindepth 1 -maxdepth 1 -type f \
        -regextype posix-extended -regex '.*/[0-9]{4}-[0-9]{2}-[0-9]{2}-[^/]+\.md' \
        | sort
)

if [[ ${#ENTRIES[@]} -eq 0 ]]; then
    echo "no dated entries in $DECISIONS_DIR — digest untouched"
    exit 0
fi

# Parse frontmatter from each entry: title, tags, supersedes.
declare -A TITLE
declare -A TAGS_RAW
declare -A SUPERSEDED  # slug → 1 if some newer entry says `supersedes: <slug>`

for entry in "${ENTRIES[@]}"; do
    slug="$(basename "$entry" .md)"
    while IFS= read -r line; do
        case "$line" in
            "title:"*)
                TITLE[$slug]="${line#title: }"
                TITLE[$slug]="${TITLE[$slug]#\"}"
                TITLE[$slug]="${TITLE[$slug]%\"}"
                ;;
            "tags:"*)
                raw="${line#tags: }"
                raw="${raw#[}"; raw="${raw%]}"
                TAGS_RAW[$slug]="${raw//,/ }"
                ;;
            "supersedes:"*)
                sup_slug="${line#supersedes: }"
                SUPERSEDED[$sup_slug]=1
                ;;
        esac
    done < <(awk '
        BEGIN { in_fm = 0 }
        /^---$/ { in_fm = !in_fm; if (!in_fm) exit }
        in_fm { print }
    ' "$entry")
done

# Build the bullet corpus: one TSV line per (entry, tag, bullet).
# Columns: TAG\tBULLET\tSLUG
# Bullets containing **Always**/**Never**/**MUST** are kept here and routed
# to "Critical" downstream by the renderer.
CORPUS_TMP="$(mktemp)"
trap 'rm -f "$CORPUS_TMP"' EXIT

CRITICAL_REGEX='[*][*]Always[*][*]|[*][*]Never[*][*]|[*][*]MUST[*][*]'

for entry in "${ENTRIES[@]}"; do
    slug="$(basename "$entry" .md)"
    [[ -n "${SUPERSEDED[$slug]:-}" ]] && continue

    # Emit bullets as TSV rows: TAG\tBULLET\tSLUG (TAG="_untagged_" if no tags).
    # Awk prints only bullets (skipping fenced code blocks); the shell loop
    # fans them out across tags.
    awk '
        BEGIN { in_code = 0; in_c = 0 }
        /^```/ { in_code = !in_code; next }
        in_code { next }
        /^## Consequences/ { in_c = 1; next }
        in_c && /^## / { in_c = 0; next }
        in_c && /^[[:space:]]*[-*][[:space:]]+/ {
            sub(/^[[:space:]]*[-*][[:space:]]+/, "")
            sub(/^[[:space:]]*\[[ xX]\][[:space:]]+/, "")
            if (NF > 0) print
        }
    ' "$entry" | while IFS= read -r stripped; do
        [[ -z "$stripped" ]] && continue

        # Trim leading whitespace.
        stripped="${stripped#"${stripped%%[![:space:]]*}"}"

        if [[ -z "${TAGS_RAW[$slug]:-}" ]]; then
            printf '_untagged_\t%s\t%s\n' "$stripped" "$slug" >> "$CORPUS_TMP"
        else
            for tag in ${TAGS_RAW[$slug]}; do
                printf '%s\t%s\t%s\n' "$tag" "$stripped" "$slug" >> "$CORPUS_TMP"
            done
        fi
    done
done

# Render digest. Use awk to group by tag and route critical bullets.
{
    echo "# Decision Log Digest"
    echo
    echo "Auto-generated consolidated rules from \`docs/decisions/\`. The agent"
    echo "reads this at session start. Per-decision entries"
    echo "(\`docs/decisions/YYYY-MM-DD-*.md\`) are the human-facing reasoning. Refresh with:"
    echo
    echo '```bash'
    echo "./scripts/refresh-decisions-digest.sh"
    echo '```'
    echo
    echo "Markers that surface as Critical: \`**Always**\`, \`**Never**\`, \`**MUST**\`."
    echo

    # Critical section: dedupe on slug+bullet (dropping tag), then format.
    echo "## Critical"
    echo
    awk -F'\t' -v re="$CRITICAL_REGEX" '
        $2 ~ re { print $3 "\t" $2 }
    ' "$CORPUS_TMP" | sort -u | awk -F'\t' '{ printf "- %s _(from `%s`)\n", $2, $1 }'
    if [[ $(awk -F'\t' -v re="$CRITICAL_REGEX" '$2 ~ re' "$CORPUS_TMP" | wc -l) -eq 0 ]]; then
        echo "_No critical markers in current entries. Add **Always** or **Never**"
        echo "to bullets in \`## Consequences\` to surface them here._"
    fi
    echo

    # Per-tag section — bullets that did NOT match Critical, grouped by tag.
    echo "## Per-tag"
    echo
    awk -F'\t' -v re="$CRITICAL_REGEX" '!/Critical/ && $2 !~ re { print }' "$CORPUS_TMP" \
        | sort -u \
        | awk -F'\t' '
            function flush() {
                if (tag != "") {
                    printf "### `%s`\n\n", tag
                    for (i = 0; i < n; i++) printf "- %s _(from `%s`)_\n", bullets[i], slugs[i]
                    print ""
                }
                tag = ""; n = 0; delete bullets; delete slugs
            }
            {
                if ($1 != tag) { flush(); tag = $1 }
                bullets[n] = $2; slugs[n] = $3; n++
            }
            END { flush() }
        '
    echo

    # Index.
    echo "## Index (slug → tags)"
    echo
    for slug in $(printf '%s\n' "${!TAGS_RAW[@]}" | sort); do
        [[ -n "${SUPERSEDED[$slug]:-}" ]] && continue
        printf -- "- \`%s\` — %s\n" "$slug" "${TAGS_RAW[$slug]:-_untagged_}"
    done
    echo

    # Active entries.
    echo "## Active entries"
    echo
    for entry in "${ENTRIES[@]}"; do
        slug="$(basename "$entry" .md)"
        [[ -n "${SUPERSEDED[$slug]:-}" ]] && continue
        printf -- "- \`%s\` — %s\n" "$slug" "${TITLE[$slug]:-_(no title)}"
    done
} > "$DIGEST"

echo "refreshed $DIGEST ($(wc -l < "$DIGEST") lines, ${#ENTRIES[@]} entries)"
