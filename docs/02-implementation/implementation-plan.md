---
title: Implementation plan — per-phase engineering detail
status: current
last_updated: 2026-08-23
---

# Implementation plan

Engineering detail behind [`../00-overview/roadmap.md`](../00-overview/roadmap.md). Phases
are ordered by dependency, and each is broken into slices that end with something
demonstrable — a slice that only makes sense once the next one lands is too big.

**Convention:** every slice ships with its tests. A slice is not done because the code
compiles; it is done when a test proves the behaviour and `./gradlew build` is green.

---

## Phase 1 — Identity and entitlements

**Blocked on a human task:** the Firebase project does not exist. Start it first.

### 1.1 Token verification

✅ **Done.** Implemented as an OAuth2 resource server against Firebase's JWK set rather
than the Admin SDK — see ADR-0006. Verification needs only the project id.

| Step | Detail |
| --- | --- |
| Decoder | `NimbusJwtDecoder` + issuer and **audience** validation (`SecurityConfig`) |
| Config | `DROVI_FIREBASE_PROJECT_ID` only — no service-account credential |
| Route rules | **deny by default.** `/s/**` and `/actuator/health` are the only anonymous paths, each with a stated reason |
| Unconfigured | no project id → no decoder → `AUTH_NOT_CONFIGURED` on every console route |

⚠️ `/s/**` must be excluded explicitly. It has its own authentication (project API keys)
and must never be subjected to Firebase auth — that would break every user's integration.

**Tested:** `IdentityTest` (8) and `AuthNotConfiguredTest` (3). Signature and audience
validation is Spring Security's and is not re-tested; what is tested is everything after
verification — provisioning, the race, entitlements, deny-by-default, suspension, and that
`/s/**` is unaffected.

### 1.2 Implicit account provisioning ✅

An `accounts` row is created on first authenticated call — no signup endpoint.

Race: two concurrent first calls. Insert and catch the unique violation on
`accounts_firebase_uid_uk`; treat the violation as success. Do **not** convert this to
SELECT-then-INSERT — that reintroduces the race the index closes.

### 1.3 `/me` and entitlements ✅

`GET /me`, `GET /me/entitlements` reading `plan_catalog`. **Never** accept a limit from the
client.

### 1.4 Error envelope ✅

One `@RestControllerAdvice`, stable codes, correlation id in every response. 5xx bodies
carry a generic message and the correlation id — never a stack trace or an upstream
message.

**Phase exit:** sign in with a real Firebase user, `GET /me` returns the account and plan,
every other console route 401s without a token. ⏳ **Waiting only on a Firebase project id**
— set `DROVI_FIREBASE_PROJECT_ID` and this is live.

One thing deliberately deferred: `account_usage_month` rollups. They belong with the
monthly caps in Phase 6, and writing the counters before anything reads them would be
machinery nobody exercises.

---

## Phase 2 — Console API

Entities exist for only 5 of 18 tables. This phase adds the rest, or reaches them with
`JdbcTemplate` — decide per table, and do not add an entity nothing needs.

### 2.1 Projects

`POST/GET /api/v1/projects`, `GET/PATCH/DELETE /api/v1/projects/{id}`.

| Concern | Rule |
| --- | --- |
| `project_key` | generated unguessable; it is a credential when `auth_mode = NONE` |
| Ownership | **always** resolve a project *with* the caller's `account_id`. Never trust an id from the path |
| Plan limit | `max_projects` checked at create |
| Archive | soft; `isServing()` already gates the runtime |

### 2.2 API keys

`POST /api/v1/projects/{id}/keys`, `DELETE .../keys/{keyId}`.

The raw key is returned **once**, in the create response, and never stored — only
`key_hash` (SHA-256) and `key_prefix`. Reuse `SandboxAuthenticator.sha256` so issuing and
verifying can never disagree.

### 2.3 Data collections and records

CRUD plus **bulk seed**, the highest-volume write path in the system.

| Concern | Rule |
| --- | --- |
| Quota | `requireCapacityFor(n, bytes)` **before** inserting, for the whole batch |
| Batching | insert in chunks; do not build one transaction around 10k rows |
| `record_key` | derived from `key_field`, written back into the payload |
| Counters | never touched from Java — the trigger owns them |

### 2.4 Spec and rules

Read endpoints for API groups, endpoints and schemas. Write endpoints for rules: create,
reorder (priority), enable/disable, set `remaining_uses`.

### 2.5 Inspector

`GET /api/v1/projects/{id}/requests` — a keyset-paginated tail of `mock_request_log`.
Keyset, not offset: this is the fastest-growing table and offset paging degrades on it.

**Phase exit:** create a project, seed data, add a rule, call the sandbox, see the call in
the inspector — all over HTTP, no SQL.

---

## Phase 3 — Generation

### 3.1 The adapter and the ledger — one slice, deliberately

Do **not** ship the adapter before the ledger and caps. An adapter that can spend before
spend can be measured or stopped is the single most expensive mistake available here.

| Step | Detail |
| --- | --- |
| `AiProvider` interface | resolved by bean name from `ai_provider_config.adapter_bean` |
| `geminiProvider` | Gemini's REST API (`generativelanguage.googleapis.com`), key in the `x-goog-api-key` header. **Verify model ids against Google's docs, never memory** — they move fast, and a wrong id fails at request time |
| `AiCallLedger` | writes an `ai_call` row for **every** call, success or failure |
| `SpendGuard` | checks the kill switch and both daily caps **before** the call; records `CAPPED` when it refuses |
| Pricing | resolved from `model_pricing` at the rate in force at call time |

⚠️ **Never inside a transaction.** Generation takes minutes; a free-tier pool dies holding
a connection that long. Commit → call → record.

**Test:** with `ai.enabled = false`, a generation fails closed with `CAPPED` and makes no
outbound call. With the cap exceeded, the same. Both write a ledger row.

### 3.2 Job runner

`generation_job` moves `QUEUED → RUNNING → SUCCEEDED|FAILED`, with `attempt` for retries —
unparseable model output is a retry, not a failure. In-process `@Scheduled` poller at one
instance; claim a job with a conditional `UPDATE … WHERE status = 'QUEUED'` so two pollers
cannot claim the same row.

### 3.3 The pipeline

| Purpose | Produces | Notes |
| --- | --- | --- |
| RESEARCH | a description of the real product's API surface | grounding is open decision M — supplied docs vs. web search |
| SPEC | `api_collection`, `api_endpoint`, schemas, envelopes | must set `path_template` **verbatim** |
| SEED | `sandbox_collection`, `sandbox_record` | quota-checked; **synthetic data only** |

Structured outputs, not free-text parsing. Validate before writing: a malformed spec must
fail the job, never half-populate a project.

### 3.4 Chat and the tool surface

Threads, messages, and tools the model may call. **Scope the tools structurally:**

INVARIANT: a tool call cannot touch a plan, a quota, an `app_config` row, a provider
credential, or any project other than the one in scope. The tool signatures must make
those unreachable — do not rely on a prompt saying so. Researched text is data, never
instructions.

**Phase exit:** one sentence produces a `READY` project whose base URL serves generated
endpoints from generated data.

---

## Phase 4 — Console UI

Next.js + React + TypeScript (ADR-0005). Types generated from the console API's OpenAPI —
never hand-written and never allowed to drift.

| Slice | Delivers |
| --- | --- |
| 4.1 Auth shell | Firebase web sign-in, session, protected routes |
| 4.2 Projects | list, create, the base URL as the hero element; the key-shown-once moment |
| 4.3 Chat | the primary surface; streams generation progress |
| 4.4 Collection browser | API groups → endpoints → schemas |
| 4.5 Data inspector | records behind each endpoint, editable, quota visible **before** a write fails |
| 4.6 Rules | visibly secondary to data |
| 4.7 Traffic inspector | live tail; platform vs simulated errors visually distinct |

Never render sandbox content as trusted HTML — records hold arbitrary user- and
model-generated data.

---

## Phase 5 — Hardening · v1 cut line

| Slice | Detail |
| --- | --- |
| 5.1 Retention purge | `mock_request_log` older than the plan's `log_retention_days`. Batched deletes; the table has no purge today and will fill the database first |
| 5.2 Job sweeper | reclaim `RUNNING` jobs older than `ai.job.timeout.seconds` |
| 5.3 Rate limiting | per project key and per IP on `/s/**`; in-process at one instance |
| 5.4 **Per-project error envelope** | a generated error shape per project, so a 404 looks like the imitated product's 404. Until this lands, a replica's error path is not faithful |
| 5.5 Observability | correlation ids, structured logs, alerts on spend, storage and unmatched-route rate |

---

## Phase 6 — Monetization

Plan enforcement for the limits not yet checked (endpoints per project, requests/month,
tokens/month), real prices, a billing provider, usage dashboards, and an explicit answer to
*what happens to a project that exceeds a lowered limit* — read-only is usually kinder than
deletion.

---

## Cross-cutting rules for every phase

1. Tests ship with the slice. Anything touching SQL uses the real embedded Postgres.
2. A schema change is a forward-only migration. Never edit an applied one.
3. A contract change updates `docs/03-api/`, `shared/api-contract.md` and the context
   folder in the same change.
4. No secret value in the repo, ever.
5. Walk `docs/05-security/security-checklist.md` for anything touching a route, a
   dependency, an env var, a DB grant, or config.
6. Never weaken a control to make something work — surface the constraint instead.
