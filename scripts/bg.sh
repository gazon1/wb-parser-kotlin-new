#!/usr/bin/env bash
# Runs a command in the background and writes its output to a log file.
#
# Why this exists
# ---------------
# A long command whose output is piped into `tail` is invisible until it ends: the
# pipe buffers everything, and the progress the command prints — "step 3 of 11" —
# arrives all at once, at the end. The agent that waits for it then polls: ten
# `sleep 30 && tail` is ten rounds of context spent on nothing, and ten minutes
# of wall-clock time during which nothing was learned.
#
# The output is a file. The file can be read while the command runs, and the
# command's own progress is visible in it.
#
# Usage
# -----
#     scripts/bg.sh ./check.sh
#     scripts/bg.sh ./gradlew test
#
# The log path is printed on stdout. The process is left running; the caller
# decides when to look at the log and when to stop waiting.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -eq 0 ]]; then
    echo "usage: scripts/bg.sh <command> [args...]" >&2
    exit 2
fi

LOG="${BG_LOG:-$(mktemp "${TMPDIR:-/tmp}/bg-XXXXXXXX.log")}"

"$@" > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$LOG.pid"

echo "$LOG"
# The pid goes to stderr: the log path on stdout is what a caller captures.
echo "pid: $PID — the output is in the log; read it while the command runs." >&2
