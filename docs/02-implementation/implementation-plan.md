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

## Phase 1 — Identity and entitlements ✅

Built and tested. It goes live the moment `DROVI_FIREBASE_PROJECT_ID` is set; until then
the console fails closed with `AUTH_NOT_CONFIGURED` and sandboxes are unaffected.

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

### 2.1 Projects ✅

`POST/GET /api/v1/projects`, `GET/PATCH/DELETE /api/v1/projects/{id}`.

| Concern | Rule |
| --- | --- |
| `project_key` | generated unguessable; it is a credential when `auth_mode = NONE` |
| Ownership | **always** resolve a project *with* the caller's `account_id`. Never trust an id from the path |
| Plan limit | `max_projects` checked at create |
| Archive | soft; `isServing()` already gates the runtime |

### 2.2 API keys ✅

`POST /api/v1/projects/{id}/keys`, `DELETE .../keys/{keyId}`.

The raw key is returned **once**, in the create response, and never stored — only
`key_hash` (SHA-256) and `key_prefix`. Reuse `SandboxAuthenticator.sha256` so issuing and
verifying can never disagree.

### 2.3 Data collections and records ✅

CRUD plus **bulk seed**, the highest-volume write path in the system.

| Concern | Rule |
| --- | --- |
| Quota | `requireCapacityFor(n, bytes)` **before** inserting, for the whole batch |
| Batching | insert in chunks; do not build one transaction around 10k rows |
| `record_key` | derived from `key_field`, written back into the payload |
| Counters | never touched from Java — the trigger owns them |

### 2.4 Spec and rules ✅

Read endpoints for API groups, endpoints and schemas. Write endpoints for rules: create,
reorder (priority), enable/disable, set `remaining_uses`.

### 2.5 Inspector ✅

`GET /api/v1/projects/{id}/requests` — a keyset-paginated tail of `mock_request_log`.
Keyset, not offset: this is the fastest-growing table and offset paging degrades on it.

**Phase exit:** create a project, seed data, add a rule, call the sandbox, see the call in
the inspector — all over HTTP, no SQL.

✅ **Complete.** A whole working sandbox — project, data, endpoints, rules — can be built
over HTTP with no SQL at all, and the traffic is inspectable.

Next is **Phase 3 — generation**, whose first slice is the provider adapter *together with*
its ledger and spend caps. Do not ship one without the other.

---

## Phase 3 — Generation

### 3.1 The adapter and the ledger — one slice, deliberately ✅

Shipped together, as required. An adapter that can spend before spend can be measured or
stopped is the single most expensive mistake available here. Design record: **ADR-0009**.

| Step | State |
| --- | --- |
| `AiProvider` interface | ✅ resolved by bean *name* from `ai_provider_config.adapter_bean`. Implementations are package-private, so nothing can inject one by type |
| `geminiProvider` | ✅ `integration/gemini/GeminiProvider`. Key in the header the config row names. **Model ids are not in the code** — they come from `app_config` routing, because a wrong id fails at request time and must be fixable with an UPDATE |
| `AiCallLedger` | ✅ an `ai_call` row for **every** call — OK, ERROR, TIMEOUT, REFUSED and CAPPED. `REQUIRES_NEW`, so a failing job cannot roll back the record of what it spent |
| `SpendGuard` | ✅ kill switch, then platform daily cap, then account daily cap — cheapest first, so an incident does not gate the kill switch behind a query |
| `ModelRouter` | ✅ `ai.model.<PURPOSE>` → `ai.model.default` → the provider row's own model |
| Pricing | ✅ `model_pricing` at the rate in force **at call time**, integer micro-USD throughout |
| `AiGateway` | ✅ the only caller of an `AiProvider`, and it throws if invoked inside a transaction |

⚠️ **Never inside a transaction.** Generation takes minutes; a free-tier pool dies holding
a connection that long. Commit → call → record. This is now enforced, not just advised:
`AiGateway.call` checks `TransactionSynchronizationManager` and throws.

Every default fails closed — a missing `ai.enabled` row reads as *off*, a missing cap reads
as *zero*. See ADR-0009 for why the two failure directions are not symmetric.

**Tested** — 28 tests, no API key and no network:

- `AiSpendControlsTest` (16) drives the gateway against a stub provider registered the same
  way a real one is: a row naming a bean. The stub counts its calls, so a cap is asserted as
  *nothing was sent*, not merely *something was thrown*. Covers the kill switch, both daily
  caps, the UTC day boundary, cost at the rate in force, a scheduled future price rise, all
  three fail-closed configuration paths, and the transaction refusal.
- `GeminiProviderTest` (12) runs the adapter against a real HTTP server on localhost:
  the system-instruction/contents separation, the auth header, structured output, reasoning
  tokens counted as output, refusal vs. error, and the read timeout.

**Not done in this slice:** `AiUsageController` or any HTTP surface. Nothing user-facing
reaches the gateway until 3.2 and 3.4 exist, which is deliberate — the controls were built
before the thing that spends, not alongside a route that could exercise it.

### 3.2 Job runner ✅

`generation_job` moves `QUEUED → RUNNING → SUCCEEDED|FAILED`, claimed by an in-process
`@Scheduled` poller. **No handlers yet** — 3.3 supplies them — so the runner claims nothing
in production today.

| Piece | Detail |
| --- | --- |
| `JobStore` | every read and write of `generation_job`, all short transactions |
| `JobRunner` | one claim per tick, one job in flight. Owns *every* status transition |
| `JobHandler` | the SPI 3.3 implements. Called with no transaction open |
| `SchedulingConfig` | `@EnableScheduling`, which Boot does **not** do for you |

**Claiming.** `FOR UPDATE SKIP LOCKED` inside the conditional `WHERE status = 'QUEUED'`.
Both stop two runners taking the same row, but the plain conditional update makes the loser
block on the winner's lock and then match nothing — it waits for a write it cannot win.

The claim also **filters on the kinds a handler exists for**. Without that, one queued job
of an unhandled kind sits at the head of the queue, is re-claimed every tick forever, and
nothing behind it runs. The full test suite found this; the isolated tests could not.

**`attempt` is incremented at claim, not at failure.** A job that kills its runner every
time would otherwise be retried forever.

**Three failure classes**, because they want different things:

| Class | Example | Outcome |
| --- | --- | --- |
| Retryable | unparseable model output | requeue, bounded by `ai.max.attempts` |
| Terminal | the model `REFUSED` | `FAILED` at once — asking again spends money to be told no twice |
| Nobody's fault | spend capped, no provider configured | requeue **without charging an attempt**, and pause |

That third row is the one worth defending. A weekend with the kill switch off must not
quietly exhaust every queued job's retries and leave them `FAILED` on Monday for a reason
that had nothing to do with them.

**Pausing** is only for states affecting every job equally. A global pause triggered by one
unrunnable job is head-of-line blocking wearing a different hat.

**Cadence is a Spring property; the on/off switch is an `app_config` row.** `@Scheduled`
resolves its interval once at startup and cannot read a table — but the half that matters
during an incident (`ai.job.runner.enabled`) is ops-changeable, and turning it off leaves
jobs `QUEUED` rather than failing them.

**Tested** — 20 tests, no API key and no network. `GenerationJobRunnerTest` (19) drives the
state machine directly against a stub handler. `JobSchedulingTest` (1) is the only test that
waits on the clock, and it exists because a missing `@EnableScheduling` would leave every
job `QUEUED` with no error to explain it — the same shape as the inert `@Cacheable`
annotations already in this codebase. Verified to fail when the annotation is removed.

⚠️ **Still missing: the sweeper.** A runner killed mid-job leaves its row `RUNNING` and
nothing reclaims it, so `ai.job.timeout.seconds` remains decorative. That is Phase 5.2.

### 3.3 The pipeline

| Purpose | Produces | State |
| --- | --- | --- |
| RESEARCH | a description of the real product's API surface | ✅ **done** |
| SPEC | `api_collection`, `api_endpoint`, `sandbox_collection` | ✅ **done** |
| SEED | `sandbox_record` | ✅ **done** |

⚠️ **Correction to the original table:** it listed `sandbox_collection` under SEED. The
foreign keys do not allow that — a data-backed `api_endpoint` requires its collection to
exist, and the database refuses the endpoint otherwise. SPEC declares the collections, which
are *structure*; SEED fills them with *data*.

#### RESEARCH ✅

Decision M is resolved — **ADR-0010**: documentation is recommended, never required, and
nothing is ever fetched.

| Input | Behaviour |
| --- | --- |
| docs supplied | primary source; outranks the model's recollection |
| no docs, `agentResearchOnly` set | runs from the model's own knowledge |
| neither | **fails terminally**, asking for one or the other |
| `docsUrl` | provenance, shown back to the user. Never fetched |

The opt-in is the point: missing docs and *declined* docs are different requests, and a
silent fallback would make the recommendation decorative. Findings carry a `confidence` and
`uncertainties` so a user can see what they traded away.

Supplied docs are truncated to `ai.research.max.docs.chars` before they are billed, and reach
the model in the user turn — never the system instruction. RESEARCH holds no tools and writes
to no project table.

Job parameters live in the new `generation_job.input` jsonb column. Every kind needs different
ones, and encoding them into `prompt` would put a parser between a user's words and the work.

Structured outputs, not free-text parsing. Validate before writing: a malformed spec must
fail the job, never half-populate a project.

#### SPEC ✅

Three phases in a fixed order, and the order is the design: **call the model with no
transaction open, validate the whole plan with nothing written, then write all of it in one
transaction.**

A project with three of its eight routes is worse than one with none — it looks finished, so
the user integrates against it and meets the rest as 404s from their own code.

`SpecPlan` rejects, before anything is written, what would otherwise surface at request time:

| Rejected | Would otherwise be |
| --- | --- |
| a GET with two path params and no stated key | an endpoint that resolves no record, at request time |
| a duplicate `method + path` | a 409 partway through the write |
| an endpoint bound to an undeclared collection | a composite-key violation after earlier rows landed |
| no collections, or no endpoints | a project that serves nothing |

Writing goes through `ApiSpecService` and `SandboxDataService`, not the repositories — they
already enforce ownership, plan limits, verbatim paths and same-project binding, and a second
write path is one that eventually stops checking something the first one checks.

Three boundaries worth keeping:

- **Generation never creates a project.** `ProjectService.create` is where the plan's project
  limit is enforced; a pipeline that could conjure projects would route around it. A SPEC job
  with no project fails terminally, before the model is called.
- **Generation never renames one either.** The user chose that name. The model's suggestion is
  returned for the console to offer.
- **Auth mode *is* set from research** — that is a property of the imitation, not a user
  preference. A replica that waves everything through never exercises the caller's auth path.

The plan's endpoint ceiling is told to the model as well as enforced after it. A model that
knows the limit picks the endpoints that matter; one that does not produces forty and has half
rejected.

#### SEED ✅

**One collection per job.** That is the pacing, not a limitation: the free tier allows 15
requests a minute, the runner takes one job per tick, and a handler looping over eight
collections would defeat both. A failing collection also retries alone.

The response schema is built from the `recordSchema` SPEC produced, so records share the
collection's fields rather than each inventing their own.

| Guard | Why |
| --- | --- |
| duplicate ids within a batch → retry | both rows would be written; `RecordWriter` checks the database, and neither is in it yet |
| a full project → **terminal** | the next attempt writes the same rows into the same full project |
| record count clamped to `ai.seed.records.max` | model-generated rows are billed per token |

⚠️ **Synthetic data is enforced by instruction, not by a check**, and that is stated rather
than implied. There is no structural control where the step's job is to invent content, and
the obvious mechanical check — rejecting Luhn-valid card numbers — would reject exactly the
values a faithful Stripe mock *should* contain. The prompt names the published test-value
convention instead.

### 3.4 Chaining, chat, and the tool surface

#### Chaining ✅

RESEARCH → SPEC → one SEED per collection → the project is `READY`. In `GenerationPipeline`,
the only class that knows the order — the runner stays generic.

| Decision | Reason |
| --- | --- |
| a job's success and its successors write in **one transaction** | split, the chain breaks both ways: a successor against a predecessor never marked succeeded, or a success with nothing following and a generation that stops silently |
| `JobChain.after` is a **calculation**, not a write | which is what makes the above possible |
| SEED fans out **one job per collection** | not parallelism — the runner still takes one job per tick. It paces the calls against 15/minute, and a collection that will not generate retries alone |
| the last step asks **"is anything else outstanding?"** | it cannot know it is last. Counting expected steps would need the count to survive a retry, a requeue and a failure |
| `GENERATING` stops the sandbox serving | routes and data appear together, not one endpoint at a time |
| a terminal failure marks the project `FAILED` | a project that merely never becomes ready is indistinguishable from one still working, forever |

**HTTP:** `POST /api/v1/projects/{id}/generations` (202) and `GET` for the history. See
`docs/03-api/console-api.md`.

**Phase exit criterion, as a test:**
`GenerationPipelineTest.oneSentence_producesAReadySandboxThatServesGeneratedData` — starts at
an HTTP POST, ends by calling the sandbox over HTTP, no SQL in between and no API key anywhere.

#### Chat and the tool surface ❌

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
