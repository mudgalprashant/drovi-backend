---
title: Platform security
status: current
last_updated: 2026-08-23
---

# Platform security

[application-security.md](application-security.md) covers the application — tenancy, auth,
prompt injection, spend. This file covers everything under it.

## Secrets

INVARIANT: the app **fails to start** when a required secret is missing — never a default.
INVARIANT: every secret is rotatable **without downtime** — publish new alongside old →
switch → revoke old. If a secret cannot be rotated that way, that is a design defect to fix
*before* it becomes an incident.
INVARIANT: **no secret value in any repo** — not in code, config, tests, docs, fixtures or
commit messages.

### Where they live

Two encrypted stores, neither in git (ADR-0007). Operational detail, including the
step-by-step and the rotation runbook, is in `dev-ops/docs/secrets.md`.

| Store | Holds |
| --- | --- |
| **Render → Environment** | everything the running service needs |
| **GitHub → Environment `production`** | anything CI needs, behind required reviewers |

**No external vault.** Not because one would be worse, but because at this size it would
add a bootstrap credential without removing a real risk: a vault needs a token to read it,
so "no secrets anywhere" is never achievable — you only choose where the last one lives.
Here the last one is an MFA-protected Render login that never touches disk. ADR-0007 lists
the triggers for revisiting.

### Never a repository secret

CI secrets go in a GitHub **Environment** with required reviewers, never a repository
secret. A repository secret is readable by *any* workflow, including one added in a pull
request from a fork — and against a public repo, a PR that adds a workflow printing every
secret it can see is the first move an attacker makes.

### Not everything called config is a secret

Treating a non-secret as secret has its own cost: it ends up in one dashboard, undocumented,
and nobody can reproduce a local run. `DROVI_FIREBASE_PROJECT_ID` is **not** a secret — it
appears in every web client's config, and verification needs no service-account credential
(ADR-0006). There is no Firebase credential in this system to protect.

### Production credentials never land on a laptop

Local work needs none of them: the tests start their own Postgres, identity needs only a
non-secret project id, and generation should use a separate low-limit key. This removes the
entire class of "my machine was compromised" incidents rather than mitigating it.

### Highest-consequence credentials

| Credential | Why it is top-tier | Compromise means |
| --- | --- | --- |
| `DROVI_GEMINI_API_KEY` | metered spend with no natural ceiling | an unbounded bill, and generation on your account |
| Production DB credentials | every tenant's sandbox data at once | full multi-tenant breach |
| Static CI cloud credentials | everything the pipeline can reach | full infrastructure compromise |
| Your Render login | every production secret at once | everything above, together — **MFA is not optional here** |
| A project API key | one sandbox | limited — but users reuse keys across systems, so treat it as real |

There is deliberately **no Firebase credential** on this list. Verifying an ID token needs
only the project id (ADR-0006), so the highest-value credential in most Firebase
deployments simply does not exist here.

Note what moved to the top as a consequence of ADR-0007: with secrets in Render's
environment, **the Render login is now the credential that unlocks the others.** That is the
honest trade — fewer moving parts, one account that matters more.

## Database hardening

INVARIANT: the app never connects as superuser or schema owner. An injection bug should be
a read, not a `DROP`.

| Role | Rights |
| --- | --- |
| `drovi_owner` | DDL — migrations only |
| `drovi_app` | DML only, no CREATE/DROP/TRUNCATE |
| `drovi_readonly` | SELECT — support, break-glass |

⚠️ **Accepted risk, recorded rather than pretended away:** Supabase free offers one broad
user, so this split is **not achieved today**. Revisit if the project leaves the free tier.

INVARIANT: `sslmode=verify-full` with a real CA bundle. `require` alone encrypts but does
not verify the host — an active MITM still works.
INVARIANT: Postgres is never reachable from the public internet. Verify from an external
host; do not assume.

Backups must be encrypted **and restore-tested**. An untested backup is not a backup.

## Repository and CI

INVARIANT: `main` is protected by configuration, not convention.
INVARIANT: third-party GitHub Actions are **SHA-pinned**, never floating tags — tags move.
INVARIANT: top-level `permissions: contents: read`; elevate per job only.
INVARIANT: production secrets live in a GitHub **Environment** with required reviewers. A
PR build must never be able to read them.
INVARIANT: prefer OIDC over any long-lived cloud key in CI — a static cloud key is the
single highest-value target in the system.

Secret scanning with **push protection** is the only control that prevents a leak rather
than reporting one; a pre-commit `gitleaks` hook is the substitute if the plan lacks it.

CODEOWNERS on `.github/workflows/`, `db/migration/` and `docs/05-security/` — a PR editing
the pipeline can exfiltrate every secret the pipeline can read.

## Supply chain

INVARIANT: no dynamic versions (`+`, ranges). A build that can change without a commit is a
vulnerability.
INVARIANT: deploy by image **digest**, never a floating tag — otherwise you cannot prove
what is running.

The Gradle wrapper checksum is pinned and `mavenCentral()` is the only repository. Gradle
*plugins* execute arbitrary code at build time — scrutinise them more than libraries.
Produce an SBOM per release so "are we affected?" takes minutes.

## Infrastructure

INVARIANT: the container runs as **non-root**, from a slim base, with no shell or package
manager in the runtime image.
INVARIANT: secrets are injected at runtime — never `COPY`d or passed as `ARG`; they persist
in image layers forever.
INVARIANT: public surface is 443 only; 8080 and 5432 closed externally.
INVARIANT: `/actuator/env` and `/actuator/heapdump` are never publicly reachable. `env`
prints configuration; heap dumps contain secrets and user data. Only `health` is exposed.

TLS 1.2+ with HSTS. Security headers (`nosniff`, `Referrer-Policy`). CORS allowlist only —
never `*` with credentials.

⚠️ **CORS has a wrinkle here.** The console needs a strict allowlist. `/s/**` is different:
it is called server-to-server by other people's applications, and may legitimately need
permissive CORS if a user's browser code calls their sandbox. Decide it deliberately per
surface; do not apply one policy to both.

## Human access

Named accounts, MFA, least privilege. Production access is **an event that leaves a
record**, not a habit: state the reason, prefer `drovi_readonly`, snapshot before any
write, second reviewer, log it.

INVARIANT: offboarding rotates everything the person could have **read or downloaded** —
not just their login. This is the step that gets skipped.

## Incidents

Severity 1 (active breach) → 4 (hardening gap). Order: **contain → preserve evidence →
assess → notify → recover → postmortem.**

Rotate before investigating when a credential is involved. Blameless postmortem; an
incident that changes nothing recurs.

Operational procedures live in `dev-ops/docs/runbook.md`. The two most likely incidents
here are **model spend running away** and **the database filling up**, and both have
step-by-step procedures there.
