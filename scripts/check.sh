#!/usr/bin/env bash
# check.sh — Full local verification for wb-parser-kotlin project
# Runs: domain:test → infrastructure:test → tests:test → app:compileKotlin
# Usage: ./check.sh   (or SKIP_DB=1 ./check.sh if DB is unavailable)

set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== [1/4] domain:test ===${NC}"
./gradlew :domain:test --no-daemon --quiet || {
    echo -e "${RED}domain:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}domain:test passed${NC}"

echo -e "${YELLOW}=== [2/4] infrastructure:test ===${NC}"
./gradlew :infrastructure:test --no-daemon --quiet || {
    echo -e "${RED}infrastructure:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}infrastructure:test passed${NC}"

echo -e "${YELLOW}=== [3/4] tests:test ===${NC}"
./gradlew :tests:test --no-daemon --quiet || {
    echo -e "${RED}tests:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}tests:test passed${NC}"

echo -e "${YELLOW}=== [4/4] app:compileKotlin ===${NC}"
./gradlew :app:compileKotlin --no-daemon --quiet || {
    echo -e "${RED}app:compileKotlin FAILED${NC}"
    exit 1
}
echo -e "${GREEN}app:compileKotlin passed${NC}"

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
