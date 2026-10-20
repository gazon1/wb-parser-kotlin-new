# Security policy

## What is in scope

This repository is a crawler for the Wildberries storefront. A vulnerability in
it is anything that lets a third party read or change data that belongs to
the operator, or makes the crawler fetch something it should not fetch.

Concretely, that means:

- the crawler following or being redirected to a host it was not told to
  fetch, and sending that host the operator's credentials;
- the crawler's HTTP client accepting a response it should reject — a
  certificate it does not trust, a redirect loop, a response larger than the
  limit it claims;
- the application's REST API — its read-only role, the API key that guards
  the administrative endpoints, the CORS and CSRF configuration;
- the database layer — anything that lets a request read or write rows that
  belong to another target;
- the deployment — the container image, the Compose file, the Ansible
  playbook, the SSH keys and the API keys they carry.

## What is not in scope

- The Wildberries API itself. A request that the storefront rejects, or a
  response it changes without notice, is not a vulnerability here.
- Rate limiting and anti-bot evasion. The crawler is a client of somebody
  else's service, and how gently it is asked to behave is a product
  decision, not a security boundary.
- Vulnerabilities in the dependencies, unless the way this repository uses
  them turns a dependency's weakness into this repository's exposure. Those
  are reported to the dependency first (Dependabot tracks them), and the
  finding is welcome here as well if the usage is the problem.
- Findings that need an operator to hand over their own credentials, their
  own database, or their own machine.

## How to report

Report it privately, through GitHub's
[private vulnerability reporting](https://github.com/gazon1/wb-parser-kotlin-new/security/advisories/new)
on this repository. If that is unavailable to you, open a regular issue
without the exploit details and ask for a private channel.

Please include: what the vulnerability lets an attacker do, the request or
input that triggers it, and the version or commit you tested.

## What happens next

An acknowledgement within a few days, and an assessment of the report
after that. There is no published SLA: this repository is maintained by one
person, and a promise about a response time is a promise that will be broken
by the next absence.

Fixes land as a pull request with a regression test where a test can
express the defect. A finding that cannot be fixed gets an issue that says
so, rather than silence.

## Supported versions

The crawler is deployed from the `main` branch. There are no release
branches and no long-term support versions: the version that matters is the
one on `main`, and the fix for a reported vulnerability is a commit on it.
