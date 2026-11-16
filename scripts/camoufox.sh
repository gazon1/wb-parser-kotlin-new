#!/usr/bin/env bash
# Installs or verifies the pinned Camoufox build.
#
#   scripts/camoufox.sh install   install the package and fetch the build
#   scripts/camoufox.sh verify    check that the machine has the pinned build
#
# The version and the build are pinned here and nowhere else: the runtime reads the
# build the package recorded and the deployment names its own pin through
# CAMOUFOX_BUILD. A different build is a different browser, so it is refused rather
# than used.
set -euo pipefail

CAMOUFOX_PACKAGE_VERSION="${CAMOUFOX_PACKAGE_VERSION:-0.5.9}"
CAMOUFOX_BUILD="${CAMOUFOX_BUILD:-browsers/official/158.0.1-beta.41-3f2eb1d0}"
CAMOUFOX_HOME="${CAMOUFOX_HOME:-$HOME/.cache/camoufox}"
CONFIG="$CAMOUFOX_HOME/config.json"

die() {
  echo "camoufox: $*" >&2
  exit 1
}

verify() {
  [[ -f "$CONFIG" ]] || die "there is no Camoufox install in '$CAMOUFOX_HOME' ('$CONFIG' does not exist). Run scripts/camoufox.sh install."

  local active
  active="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("active_version", ""))' "$CONFIG")"
  [[ "$active" == "$CAMOUFOX_BUILD" ]] || die "the installed build is '$active', but this deployment pins '$CAMOUFOX_BUILD'."

  local binary="$CAMOUFOX_HOME/$active/camoufox-bin"
  [[ -f "$binary" ]] || die "the build '$active' has no executable at '$binary'."
  [[ -x "$binary" ]] || die "'$binary' is not executable."
  echo "camoufox: $active is installed at $CAMOUFOX_HOME"
}

install() {
  command -v uv >/dev/null 2>&1 || die "uv is not installed. Install it first: https://docs.astral.sh/uv/"
  uv tool install "camoufox==$CAMOUFOX_PACKAGE_VERSION"
  camoufox fetch
  verify
}

case "${1:-verify}" in
  install) install ;;
  verify) verify ;;
  *) die "usage: $0 [install|verify]" ;;
esac
