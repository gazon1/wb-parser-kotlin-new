# ==============================================================================
# 🚀 DEPLOY — ship a pre-built image to the VPS via Ansible
# ==============================================================================
# Recipe available as deploy::<recipe> or via the alias `just d`.
#
#   just deploy             # check → deploy current HEAD tag
#   just deploy <tag>       # deploy an already-pushed tag
#
# The CI pipeline (CIpublisher) is responsible for:
#   ./check.sh  →  docker build  →  docker push
# `just deploy` assumes the image already exists in GHCR and only runs Ansible.
#
# To build and push manually (one-shot, no CI):
#   just deploy::build
#   just deploy::push
# ==============================================================================

IMAGE_OWNER := env_var_or_default("IMAGE_OWNER", "gazon1")
REGISTRY := "ghcr.io"
IMAGE := REGISTRY + "/" + IMAGE_OWNER + "/wb-parser"
# The browser runtime is a second image: the crawler cannot fetch a page without a
# real browser, and the browser is what AdsPower provides. Both are built from the
# same commit and deployed under the same tag — the deploy names the commit once.
BROWSER_IMAGE := REGISTRY + "/" + IMAGE_OWNER + "/wb-parser-adspower"

# Where the Ansible playbooks live. The deploy contract is deliberately split:
# this repository owns the image and the compose file, Caddy-vps owns the host.
CADDY_VPS_DIR := env_var_or_default("CADDY_VPS_DIR", home_directory() / "Caddy-vps")
PLAYBOOK := CADDY_VPS_DIR / "playbooks" / "wbparser.yml"

[group('deploy')]
[doc('Deploy a pre-built image to the VPS (runs ./check.sh first)')]
deploy tag="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{ justfile_directory() }}"

    TAG="{{ tag }}"
    if [[ -z "$TAG" ]]; then
      TAG="$(git rev-parse --short HEAD)"
    fi

    if [[ ! -f "{{ PLAYBOOK }}" ]]; then
      echo "❌ Playbook not found: {{ PLAYBOOK }}"
      echo "   Set CADDY_VPS_DIR to the Caddy-vps checkout."
      exit 1
    fi

    # A tag that claims to be a commit but carries uncommitted work makes the
    # rollback story a lie: there is no such commit to go back to.
    if [[ -n "$(git status --porcelain)" ]]; then
      echo "⚠️  Working tree is dirty. The tag will not match what actually ships:"
      git status --short
    fi

    echo "🚦 Gates (./check.sh)…"
    ./check.sh

    echo "🚀 Deploying $TAG to the VPS…"
    ansible-playbook "{{ PLAYBOOK }}" \
        -e "wbparser_tag=$TAG" \
        -e "wbparser_compose_src={{ justfile_directory() }}/deploy/docker-compose.vps.yml"

    echo ""
    echo "✅ $TAG is live."

[group('deploy')]
[doc('Build the Docker image (run manually or from CI)')]
build:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{ justfile_directory() }}"

    if ! grep -q 'ghcr.io' "$HOME/.docker/config.json" 2>/dev/null; then
      echo "❌ Not logged into ghcr.io."
      echo "   docker login ghcr.io -u <user> --password-stdin"
      exit 1
    fi

    TAG="$(git rev-parse --short HEAD)"
    TARGET="{{ IMAGE }}:$TAG"
    BROWSER_TARGET="{{ BROWSER_IMAGE }}:$TAG"
    echo "🐳 Building $TARGET…"
    docker build -f Dockerfile.app -t "$TARGET" .
    echo "✅ Built $TARGET"
    echo "🐳 Building $BROWSER_TARGET…"
    docker build -f docker.adspower/Dockerfile -t "$BROWSER_TARGET" docker.adspower
    echo "✅ Built $BROWSER_TARGET"

[group('deploy')]
[doc('Push the current HEAD tag to GHCR (run manually or from CI)')]
push tag="":
    #!/usr/bin/env bash
    set -euo pipefail

    TAG="{{ tag }}"
    if [[ -z "$TAG" ]]; then
      TAG="$(git rev-parse --short HEAD)"
    fi
    TARGET="{{ IMAGE }}:$TAG"
    BROWSER_TARGET="{{ BROWSER_IMAGE }}:$TAG"

    echo "📤 Pushing $TARGET…"
    docker push "$TARGET"
    echo "✅ Pushed $TARGET"
    echo "📤 Pushing $BROWSER_TARGET…"
    docker push "$BROWSER_TARGET"
    echo "✅ Pushed $BROWSER_TARGET"

[group('deploy')]
[doc('Dry-run Ansible with --check --diff')]
dry-run tag="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{ justfile_directory() }}"

    TAG="{{ tag }}"
    if [[ -z "$TAG" ]]; then
      TAG="$(git rev-parse --short HEAD)"
    fi

    if [[ ! -f "{{ PLAYBOOK }}" ]]; then
      echo "❌ Playbook not found: {{ PLAYBOOK }}"
      exit 1
    fi

    ansible-playbook "{{ PLAYBOOK }}" \
        --check --diff \
        -e "wbparser_tag=$TAG" \
        -e "wbparser_compose_src={{ justfile_directory() }}/deploy/docker-compose.vps.yml"