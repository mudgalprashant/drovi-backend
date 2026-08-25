---
title: Roadmap — Drovi
status: current
last_updated: 2026-08-23
---

# Roadmap

Drovi builds a working replica of somebody else's production API. You name a product, an
agent researches it, and you get a base URL to paste over the real one.

This document is the **canonical plan across all repos**. Per-phase engineering detail is
in [`../02-implementation/implementation-plan.md`](../02-implementation/implementation-plan.md).

## The shape of the plan

Seven phases. Each ends with something a person can *use* — not a layer that only makes
sense once the next one lands. The order is forced by one dependency chain:

> nothing can be owned until there is an identity · nothing can be managed until there is
> a console API · nothing can be generated until spend can be capped · nothing can be sold
> until limits are enforced

```mermaid
flowchart LR
    P0[P0 · Foundation<br/>DONE] --> P1[P1 · Identity]
    P1 --> P2[P2 · Console API]
    P2 --> P3[P3 · Generation]
    P2 --> P4[P4 · Console UI]
    P3 --> P4
    P3 --> P5[P5 · Hardening]
    P4 --> P5
    P5 --> P6[P6 · Monetization]
    P6 --> P7[P7 · Beyond v1]
```

**The v1 cut line is the end of P5.** P0–P5 is a product someone can use for real work.
P6 makes it a business. P7 is everything we deliberately deferred.

---

## Phase 0 — Foundation ✅ complete

**Goal:** prove the core mechanic — that a sandbox can serve a faithful replica.

| Delivered | |
| --- | --- |
| Schema | 18 tables, Flyway, four invariants enforced by the database |
| Mock runtime | routing, rules, LIST/GET/CREATE/UPDATE/DELETE, envelopes, paging, filtering |
| Sandbox auth | NONE / BEARER / HEADER_KEY / BASIC against hashed keys |
| Quota | trigger-maintained counters, enforced on write |
| Inspector | every served call logged, including unmatched routes |
| Tests | 23, green against a real embedded Postgres |

**Exit criteria — met:** a hand-seeded project serves a faithful replica over HTTP, with
rules overriding data and quota refusing writes past the plan limit.

**What it does not do:** anything a user could reach without SQL access.

---

## Phase 1 — Identity and entitlements  🟡 mostly complete

**Goal:** a caller has a name, a plan, and limits that are the server's to decide.

Nothing else can be built first. Every console route needs a principal, and every quota
needs an account to charge.

| Deliverable | Status |
| --- | --- |
| Firebase token verification | ✅ OAuth2 resource server against Firebase's JWK set (ADR-0006) |
| Deny by default | ✅ every route authenticated except `/s/**` and health, each with a stated reason |
| Implicit account provisioning | ✅ on first authenticated call, race-safe |
| `GET /me`, `GET /me/entitlements` | ✅ limits read from `plan_catalog` |
| Error envelope + correlation ids | ✅ one handler; 5xx carries a generic message and an id |
| Usage rollups (`account_usage_month`) | ❌ deferred to Phase 6, where the monthly caps are actually enforced |

**Exit criteria:** a real Firebase user signs in, hits `/me`, sees their plan, and an
unauthenticated call to any console route is rejected. **The first half needs a Firebase
project to exist** — everything else is done and tested.

**The human task shrank.** ADR-0006 means verification needs only the Firebase **project
id**, not a service-account credential. Until one is set the server fails closed:
`AUTH_NOT_CONFIGURED` on every console route, sandboxes unaffected.

---

## Phase 2 — Console API  ✅ complete

**Goal:** everything the runtime can do becomes reachable without touching SQL.

This is the phase that makes Phase 0 useful. It is also the last one that is purely
mechanical — after this, cost and correctness get interesting.

| Deliverable | State |
| --- | --- |
| Projects | ✅ create, list, update, archive. The base URL is the artifact |
| API keys | ✅ issue and revoke; the raw key is returned **once** and never stored |
| Data | ✅ collections and records, incl. bulk seed, within quota |
| Spec | ✅ API groups and endpoints — the Postman-like view |
| Rules | ✅ create, enable/disable, one-shot, priority |
| Inspector | ✅ keyset-paged tail, with an unmatched-only view |

**Exit criteria — met.** A user creates a project, seeds data, declares endpoints, defines
a rule, calls their sandbox and sees the call in the inspector, entirely over HTTP.
`EndpointsAndRulesTest.aWholeSandbox_canBeBuiltAndServed_withNoSqlAtAll` is that criterion
as an executable test.

**Watch:** bulk seed is the highest-volume write path in the system. It must stream and
batch, and it must check quota *before* the insert, not after.

---

## Phase 3 — Generation  🟡 in progress

**Goal:** the headline feature. Describe a product in chat; get a sandbox.

| Deliverable | Detail |
| --- | --- |
| Provider adapter | ✅ `geminiProvider`, resolved by bean name from `ai_provider_config` |
| Ledger and caps | ✅ every call recorded; kill switch and daily caps enforced **before** the call. ADR-0009 |
| Job runner | ✅ claim, retry, and the three failure classes |
| Pipeline | ✅ RESEARCH · SPEC · SEED |
| Chaining | ✅ RESEARCH → SPEC → one SEED per collection → `READY`, plus the HTTP surface to start one |
| Clarifications | ✅ the system asks rather than guesses, and stops until answered (ADR-0011) |
| Wait time | ✅ seconds and a sentence, and honestly nothing while waiting on the user |
| Chat | ✅ threads and messages; the same sentence builds or revises depending on the project |
| Spec import | ✅ a pasted OpenAPI or Postman document is read directly — no research, no spec model call |
| REVISE | ✅ "make five customers' cards blocked" applied to an existing sandbox — a validated plan, not tool calls |

**3.1–3.3 and the chaining half of 3.4 are done. 3.1 was the risky half of the spending;
the tool surface is the risky half of what remains.** The spending machinery exists, is enforced, and is proved by tests that need no API key —
because a stub provider registers exactly as a real one does. The pipeline runs end to end
behind it.

**Decision M is settled** (ADR-0010): documentation is recommended, never required, and
nothing is ever fetched. A user either supplies docs or explicitly opts into having the agent
work from its own knowledge — and because accuracy is now something they can trade away,
research reports how confident it is and what to check.

**3.3 is complete.** A RESEARCH job produces findings, a SPEC job turns them into routes and
the collections behind them, and a SEED job fills one of those collections — after which the
sandbox's base URL serves generated data.

What is missing is the thing that *joins* them. Each step is enqueued on its own today;
nothing chains RESEARCH → SPEC → SEED, and nothing promotes a project from `DRAFT` to `READY`
when the chain finishes. That, and the chat surface that starts it, is 3.4.

**Exit criteria — met.** From one sentence, a project reaches `READY` with endpoints, schemas
and seed data, and its base URL serves them.
`GenerationPipelineTest.oneSentence_producesAReadySandboxThatServesGeneratedData` is that
criterion as an executable test: it starts at an HTTP POST and finishes by calling the sandbox
over HTTP, with no SQL in between.

**Phase 3 is complete.** The piece called out since Phase 0 as the risky one — a tool surface
the model can call — was met by *not having one*: a model's write is a plan the platform
validates and applies (#51).

**Every input the product goal names now works**: a described product, a pasted specification or
Postman collection, and a link — to a spec or to an API whose spec is discoverable (ADR-0012).
Reading a link is almost entirely a request-forgery problem, and the guard is where that work
went.

**The two risks that matter here**, both called out in the security docs:

1. **Spend.** ✅ Handled in 3.1. Caps and the kill switch work *before* the first real
   generation rather than after the first surprise bill, and every default fails closed.
   What remains is that they are *ceilings, not reservations*: a call that starts under the
   cap finishes above it by at most one call's worth. Set a cap below what you can afford to
   lose, not at it.
2. **Prompt injection.** 🟡 The structural half is in place — the adapter keeps Drovi's own
   words in the provider's system-instruction field and everything user- or web-derived in
   `contents`, and a test asserts the separation. The hard half is still ahead: the tool
   surface in 3.4 must be unable to reach another project, a plan, a quota or `app_config`
   *by its signatures*. A system prompt is not a control.

---

## Phase 4 — Console UI (Next.js + React + TypeScript)

**Goal:** the product becomes something you would show someone.

| Deliverable | Detail |
| --- | --- |
| Chat | the primary surface; streams a generation's progress |
| Collection browser | API groups → endpoints → schemas |
| Data inspector | the records behind each endpoint, editable |
| Rules | visibly secondary to data — the UI must not suggest scripted responses |
| Traffic inspector | live tail; platform errors visually distinct from simulated ones |
| Project settings | base URL front and centre; keys, auth mode, latency |

**Exit criteria:** a developer completes the whole loop — describe, generate, copy the base
URL, call it from their own app, watch it in the inspector — without reading any docs.

**Deploys to Render or Cloudflare, not Vercel free**: Hobby is non-commercial only, and a
paid tier breaches it (ADR-0005).

---

## Phase 5 — Hardening and operations · **v1 cut line**

**Goal:** it survives real use and real mistakes.

Everything here is a known gap today, and each one is a real incident waiting to happen.

| Deliverable | State |
| --- | --- |
| `mock_request_log` retention purge | ✅ batched, per-plan retention, bounded per run |
| Stuck-job sweeper | ✅ reclaims rather than fails, and takes the project out of `GENERATING` |
| Rate limiting on `/s/**` | ✅ per project and per caller, checked before the database |
| **Per-project error envelope** | ✅ **thread N closed.** In-character errors wear the product's shape; ours stay ours |
| Structured logging + correlation ids | ✅ the id now reaches a log line, and background work has one |
| Alerting | ✅ spend, storage, unmatched-route rate — each with a runbook procedure |

**Exit criteria:** a week of real traffic with no manual intervention, and the runbook's
procedures have each been walked once.

**Phase 5 is complete — the v1 cut line is reached.** The system purges what it writes, recovers
work a dead runner abandoned, refuses abuse, answers errors in character, and now says something
while a limit is being *approached* rather than only when a control refuses somebody.

P0–P5 is a product someone can use for real work. What follows is P6, which makes it a business.

---

## Phase 6 — Monetization

**Goal:** the free tier stops being the only tier.

| Deliverable | Detail |
| --- | --- |
| Plan enforcement end to end | project count, endpoints, requests/month, tokens/month |
| Real plan limits and prices | the seeded numbers are placeholders, never a pricing decision |
| Billing integration | provider to be chosen |
| Usage dashboards | storage, requests, model spend, per project |
| Upgrade / downgrade | including what happens to a project that exceeds a lowered limit |

**Exit criteria:** a user upgrades, their limits change immediately, and the change is
server-authoritative.

---

## Phase 7 — Beyond v1

Deliberately deferred. Listed so they are not smuggled into an earlier phase.

| Idea | Note |
| --- | --- |
| **OpenAPI / Postman import** | skip research entirely when the user already has a spec. Probably the highest-value item here |
| **Scenarios** | a named, toggleable set of rules — "declined payments", "peak load" |
| **Outbound webhook simulation** | let a sandbox call *back* into the user's app, as real payment products do |
| **Stateful flows** | a charge that moves `pending → settled` on a schedule |
| **Team accounts** | shared projects, roles |
| **Record templates / faker** | generate 10k realistic rows without 10k model tokens |
| **Self-hosting** | the Dockerfile already makes this cheap |
| **Response fidelity diffing** | call the real API once, diff it against the replica |

---

## What could invalidate this plan

Named now, so a surprise later is recognised rather than debated.

| Risk | Signal it is happening | Response |
| --- | --- | --- |
| **Generation quality is not good enough** | high unmatched-route rate; users correcting endpoints by hand | Phase 7's OpenAPI import moves forward — a supplied spec beats research |
| **Model cost per sandbox is too high for a free tier** | `ai_call` spend per `READY` project | route SEED cheaper; template-generate bulk records instead of prompting for them |
| **512 MB is not enough** | Render OOM kills | $7 instance, or move the runtime off the JVM |
| **Storage fills faster than expected** | Supabase approaching 500 MB | the P5 purge moves into P2 |
| **Prompt injection lands** | a generation mutates something outside its project | stop generation via the kill switch; the tool surface was too broad |
