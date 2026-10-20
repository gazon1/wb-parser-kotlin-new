#!/usr/bin/env python3
"""check-action-pins.py — every action is pinned to a commit, not to a tag object.

Why this exists
---------------
Pinning an action to a 40-character SHA looks like the strongest form of
pinning, and it is not. A 40-character SHA is the identifier of *some* git
object, and an annotated tag is a git object too. `refs/tags/v2.5.0` of
ossf/scorecard-action is an annotated tag, and its SHA —
46dc3187a971a9febf3aeb7f94ccde489f7bb46a — is the SHA of the *tag object*,
not of any commit.

GitHub Actions resolves that SHA happily: the action runs, the analysis runs,
the step is green. The Scorecard publication API does not: it checks that the
pinned SHA is a commit of the action's own repository, and it answered
"imposter commit: 46dc3187… does not belong to ossf/scorecard-action". The
score was computed and thrown away on every run, and the run was green.

A shape check cannot tell the two apart — both are forty hex characters — so
the question has to be asked of the upstream repository, which means the
network. That is why this check does not live in the static registry: the
local cycle must not depend on a network call, and a CI failure that is about
the network is a failure about nothing.

So the check lives where the network is a given — the `static` job of the CI
pipeline — and the gate that guards the CI surfaces
(scripts/ci/check-ci-workflows.py) checks that this script exists and that
the `static` job calls it. The same arrangement the lint auto-fix uses: the
corrector runs in CI, and the gate checks that the workflow calls it.

Usage
-----
    python3 scripts/ci/check-action-pins.py
    python3 scripts/ci/check-action-pins.py --self-test

Environment
-----------
    GITHUB_TOKEN — used for the API calls when present. The API's rate limit
    is 60 requests per hour without it, which is fewer than the number of
    pins in this repository.

Exit codes
----------
    0 — every pin is a commit
    1 — a pin is not a commit (or the API could not be reached)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

#: `uses: owner/repo@ref` — the action, the sub-path of the action inside its
#: repository, and the reference. A sub-path (`github/codeql-action/init`) is
#: part of the action, not of the repository: the repository is the first two
#: segments, and the commit lives in the repository.
USES = re.compile(r"uses:\s+([A-Za-z0-9._-]+/[A-Za-z0-9._/-]+)@([^\s#]+)")

#: A commit SHA: forty lowercase hexadecimal characters.
SHA = re.compile(r"[0-9a-f]{40}")

#: The API that answers "is this SHA a commit of this repository?".
API = "https://api.github.com/repos/{owner}/{repo}/commits/{sha}"


def pinned_actions(root: Path) -> list[tuple[str, str, Path]]:
    """Every pinned action: the action, the SHA, and the file it came from.

    A local reference — `uses: ./.github/workflows/ci.yml` — is a workflow of
    this repository, and there is nothing to pin.
    """
    found: list[tuple[str, str, Path]] = []
    for path in sorted((root / ".github" / "workflows").glob("*.yml")):
        text = path.read_text(encoding="utf-8")
        for match in USES.finditer(text):
            action, ref = match.group(1), match.group(2)
            if not SHA.fullmatch(ref):
                continue
            found.append((action, ref, path))
    return found


def repository_of(action: str) -> str:
    """The repository an action lives in: `owner/repo`, without the sub-path.

    `github/codeql-action/init` is the `init` action of the repository
    `github/codeql-action`. The commit is in the repository, so that is what
    the API has to be asked about.
    """
    parts = action.split("/")
    return "/".join(parts[:2])


def is_commit(
    owner_repo: str,
    sha: str,
    token: str = "",
    opener=urllib.request.urlopen,
) -> bool:
    """Whether `sha` is a commit of the repository `owner_repo`.

    The API answers 200 for a commit and 422 for anything else — a tag
    object, or a SHA the repository does not have. Both are the same problem
    for this check: the pin does not name a commit, so the action's own
    release rules (and the Scorecard API, which enforces them) reject it.
    """
    owner, repo = owner_repo.split("/", 1)
    request = urllib.request.Request(
        API.format(owner=owner, repo=repo, sha=sha),
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "wb-parser-kotlin-action-pins",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    try:
        with opener(request, timeout=30) as response:
            return response.status == 200
    except urllib.error.HTTPError as error:
        if error.code in (403, 404, 422):
            return False
        raise


def check(root: Path, token: str = "", opener=urllib.request.urlopen) -> list[str]:
    """Every pin that does not name a commit of its own repository."""
    findings: list[str] = []
    for action, sha, path in pinned_actions(root):
        owner_repo = repository_of(action)
        if is_commit(owner_repo, sha, token=token, opener=opener):
            continue
        findings.append(
            f"{path.name} pins `{action}` to {sha}, which is not a commit of "
            f"{owner_repo}. It is the SHA of an annotated tag object, and the "
            "action's own release rules reject it — the Scorecard API answers "
            "\"imposter commit\" and refuses the publication, so the analysis "
            "is computed and thrown away. Pin the commit the tag points at "
            "instead, and keep the version in the comment."
        )
    return findings


def run_self_test() -> int:
    """Prove the check fires on a pin that is not a commit, and stays quiet on one that is.

    The API is stubbed: the point of the control is the decision, not the
    network.
    """
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        workflows = root / ".github" / "workflows"
        workflows.mkdir(parents=True)
        (workflows / "ci.yml").write_text(
            "jobs:\n"
            "  a:\n"
            "    steps:\n"
            "      - uses: ossf/scorecard-action@"
            + "a" * 40
            + " # v2.5.0\n",
            encoding="utf-8",
        )

        calls: list[str] = []

        def opener_healthy(request, timeout=None):
            calls.append(request.full_url)
            return _FakeResponse(200, b"{}")

        def opener_broken(request, timeout=None):
            calls.append(request.full_url)
            return _FakeResponse(422, b"{}")

        clean = check(root, opener=opener_healthy)
        if clean:
            print(
                "self-test FAILED — the check reports a finding on a pin that is a commit:",
                file=sys.stderr,
            )
            for line in clean:
                print(f"  · {line}", file=sys.stderr)
            return 1
        if not calls:
            print("self-test FAILED — the check did not ask the API anything", file=sys.stderr)
            return 1

        broken = check(root, opener=opener_broken)
        if not any("not a commit" in line for line in broken):
            print(
                "self-test FAILED — the check did not detect a pin that is not a commit:",
                file=sys.stderr,
            )
            for line in broken:
                print(f"  · {line}", file=sys.stderr)
            return 1

    print("✅ self-test: the check fires on a pin that is not a commit")
    return 0


class _FakeResponse:
    """The part of an HTTP response the check reads."""

    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *args: object) -> None:
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="prove the check detects a pin that is not a commit",
    )
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    token = os.environ.get("GITHUB_TOKEN", "")
    try:
        findings = check(ROOT, token=token)
    except urllib.error.URLError as error:
        print(
            f"❌ the GitHub API could not be reached: {error}. The pins could not be "
            "verified, and a pin that is not a commit is a publication that is "
            "silently rejected.",
            file=sys.stderr,
        )
        return 1

    if findings:
        print("", file=sys.stderr)
        for finding in findings:
            print(f"❌ {finding}", file=sys.stderr)
        return 1

    pins = len(pinned_actions(ROOT))
    print(f"✅ every action is pinned to a commit of its own repository ({pins} pins)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
