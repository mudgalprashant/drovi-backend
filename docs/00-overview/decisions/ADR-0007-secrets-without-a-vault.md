# ADR-0007 — Secrets live in Render's environment, not an external vault

**Status:** accepted · **Date:** 2026-08-23

## Context

The requirement was: *no secrets locally, and none in code on GitHub.* The obvious
implementation is a dedicated secrets manager — Doppler, Infisical, or HCP Vault Secrets.

All three were evaluated against current free tiers:

| | Doppler | Infisical | HCP Vault Secrets |
| --- | --- | --- | --- |
| Free tier | 5 users, unlimited projects/envs | 5 **identities** (machine accounts count), 3 projects | ~25 secrets |
| Render | native auto-sync | integration | manual |
| Self-host | no | **yes** | no |

## Decision

**No external vault.** Secrets live in exactly two encrypted stores, neither in git:

- **Render → Environment** — everything the running service needs
- **GitHub → Environment `production`** — anything CI needs, behind required reviewers

## Consequences

**The requirement is already met without one.** Render's environment is encrypted at rest
and never touches git; `render.yaml` declares each variable `sync: false`, so the repo can
describe every variable while holding no value. The gap a vault would close is not the gap
we have.

**It removes a bootstrap credential rather than adding one.** Every vault needs a token to
read it, and that token must live somewhere — so "no secrets anywhere" is never achievable;
you only choose where the last one lives. With this model there is no vault client and no
vault token at all. The last credential is an MFA-protected Render login that never touches
disk.

**What we give up, honestly:**

- no single source of truth if a second environment ever appears
- no audit trail of who read a secret and when (paywalled on every free tier anyway)
- no automated rotation — rotation is a runbook, not a cron
- secrets exist in two dashboards rather than one

**Revisit when any of these becomes true.** Each is a reason a vault earns its keep, and
none apply yet:

- a second person needs production access — sharing a login is not access control
- a staging environment appears, so the same secret is pasted twice
- audit becomes a requirement
- rotation needs to be automatic rather than manual

**If we do adopt one, Infisical is the likely choice** — it is open source and
self-hostable, so the escape hatch exists. That matters here for the same reason Vercel was
ruled out in ADR/decision #29: a free tier that constrains a commercial product is a trap
discovered late.

**A secondary win:** the decision forced the secret/not-secret line to be drawn explicitly.
`DROVI_FIREBASE_PROJECT_ID` is *not* a secret, and treating it as one would have hidden it
in a dashboard where nobody could reproduce a local run.

Operational detail: `dev-ops/docs/secrets.md`.
