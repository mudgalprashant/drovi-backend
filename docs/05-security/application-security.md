---
title: Application security
status: current
last_updated: 2026-08-23
---

# Application security

Platform concerns — secrets, roles, CI, infrastructure — are in
[platform-security.md](platform-security.md).

## 1. Tenancy — the one unrecoverable bug

Drovi holds many customers' data in shared tables. A cross-tenant read is not a bug to fix
in the next release; it is the failure that ends the product's credibility.

INVARIANT: **a project can never read another project's rows.** Enforced by the database:
`project_id` is denormalised onto every child table *and* re-checked by a composite foreign
key, so attaching a record to another tenant's data collection fails in Postgres rather
than in a code review.

INVARIANT: **every `sandbox_record` query carries a `project_id` predicate.** The index
leads with `project_id`, so a missing predicate degrades the query loudly instead of
silently widening it.

INVARIANT: **never trust an id from a path or body.** A project id from a caller is
resolved *with* the caller's `account_id`, or one user reads another's sandbox.

Guarded by `SchemaInvariantsTest.record_attachedToAnotherProjectsCollection_rejected`.

## 2. Two authentication systems, never confused

| Surface | Caller | Credential |
| --- | --- | --- |
| Console `/api/v1/**` | the user, in a browser | Firebase ID token |
| Sandbox `/s/{key}/**` | the user's own application | a project API key |

INVARIANT: `/s/**` is excluded from Firebase authentication explicitly. Subjecting it to
console auth would break every user's integration at once.

### Firebase owns identity

The backend **verifies** ID tokens and never issues them. No password hashing, no session
store, no refresh-token rotation, no reuse detection — a large amount of security-critical
code we deliberately do not own.

### Project API keys

INVARIANT: only `key_hash` (SHA-256) and a display `key_prefix` are stored. Drovi
**cannot** redisplay a key — that is the design, not a gap to close.

Lookup is **by hash**, so the raw key never reaches the database, a log, or a slow-query
report. A project may hold several live keys so rotation needs no downtime: issue → switch
the client → revoke the old.

### When `auth_mode = NONE`, the project key *is* the credential

It is the only thing between the internet and a sandbox. Therefore:

- it must be unguessable, never sequential
- it must never be logged in full beside data
- `SANDBOX_NOT_FOUND` is returned **identically** for "no such project" and "not ready" —
  distinguishing them confirms which keys exist

### The sandbox must reject like the product it imitates

A replica that waves everything through means the caller's own auth path is never
exercised until production. That is a security feature of the product, not a nicety.

## 3. Prompt injection — the newest risk, and the least familiar

Generation researches a third party's API from material Drovi does not control: vendor
docs, web pages, user-pasted text. That content reaches a model that holds tools which
write to the database.

INVARIANT: **researched or user-supplied text is data, never instructions.**

INVARIANT: **the tool surface must not offer the operations worth attacking.** A generation
tool call must be structurally unable to change a plan, a quota, an `app_config` value, a
provider credential, or any project other than the one in scope. Scope it in the tool
signatures — a system prompt telling the model to behave is not a control.

INVARIANT: **generated seed data must be synthetic.** Real names, card numbers, emails or
identifiers scraped from a real source turn a mock service into a personal-data breach.

## 4. Spend is a security control, not just a cost control

An AI product that cannot stop spending on command has no free tier. The kill switch
(`app_config.ai.enabled`) and the daily caps bound the damage of a stolen account, an
injection loop and a runaway retry alike. They live in the database so they can be changed
during an incident without a deploy.

INVARIANT: **every model call is ledgered whether it succeeded or not.** A failed call that
consumed input tokens still costs money, and a success-only ledger under-reports exactly
when spend is running away.

INVARIANT: caps are checked **before** the call, not after.

## 5. Quota is enforced, not advertised

`QuotaService.requireCapacityFor` **throws**. It does not return a boolean, because a
quota check whose result can be dropped is a quota check that will be.

## 6. The public surface is public by design

`/s/{projectKey}/**` returns caller-controlled JSON from a Drovi-owned origin. Two
consequences to hold in mind rather than "fix":

- it can be abused as arbitrary content hosting. Rate limiting is a Phase 5 deliverable
  and **does not exist today**
- never render sandbox content as trusted HTML anywhere in the console

## 7. Data handling

| Class | Rule |
| --- | --- |
| Project API keys | hash only; never logged, never in a URL |
| Provider API key | an env var named by `ai_provider_config.api_key_env_var`; never in the database |
| Caller IPs | truncated to /24 (or /48) before storage — enough to debug, not a personal identifier |
| Sandbox record data | the user's own content; never logged wholesale |
| 5xx bodies | generic message + correlation id. An upstream provider's error text never reaches a caller |

## 8. Secrets

INVARIANT: no secret value in the repo. Config references env vars and the app **fails to
start** when a required one is missing — never a silent default.

Inventory (names only): `DROVI_DB_URL`, `DROVI_DB_USERNAME`, `DROVI_DB_PASSWORD`,
`DROVI_ANTHROPIC_API_KEY`, `DROVI_FIREBASE_PROJECT_ID`, `DROVI_FIREBASE_CREDENTIALS_B64`.

## 9. Threats mitigated by design

Enumeration (identical 404s), tenant escape (composite FK), credential replay (hash-only
storage), runaway spend (ledger + caps + kill switch), lost updates (`@Version` where
concurrent writes exist), one-shot rule races (conditional `UPDATE`).

## 10. Not yet addressed

Rate limiting, abuse of the sandbox surface as content hosting, dependency and secret
scanning in CI, per-project error-shape fidelity. All are Phase 5 deliverables.
