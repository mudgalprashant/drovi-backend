---
title: The console API
status: partially implemented
last_updated: 2026-08-23
---

# The console API

Authenticated with a Firebase ID token, deny by default. Projects, API keys and data are
implemented; the spec, rules and inspector surfaces are not.

## Shape

Base `/api/v1`. JSON. Firebase ID tokens. URLs lowercase, plural, hyphenated; JSON
`camelCase`; timestamps ISO-8601 UTC.

## Required surface

| Area | Endpoints | State |
| --- | --- | --- |
| Identity | `GET /me`, `GET /me/entitlements` | ✅ |
| Projects | `POST/GET /projects`, `GET/PATCH/DELETE /projects/{id}` | ✅ |
| API keys | `POST/GET /projects/{id}/keys`, `DELETE .../keys/{keyId}` | ✅ |
| Data collections | `GET/POST /projects/{id}/collections`, `GET/PATCH/DELETE .../{cid}` | ✅ |
| Records | `GET/POST .../records`, `GET/PATCH/DELETE .../records/{recordKey}` | ✅ |
| API groups | `GET/POST /projects/{id}/api-groups`, `DELETE .../{groupId}` | ✅ |
| Endpoints | `GET/POST /projects/{id}/endpoints`, `GET/PATCH/DELETE .../{endpointId}` | ✅ |
| Rules | `GET/POST .../endpoints/{eid}/rules`, `PATCH/DELETE /projects/{id}/rules/{ruleId}` | ✅ |
| Inspector | `GET /projects/{id}/requests` — keyset paged | ✅ |
| Generations | `POST/GET /projects/{id}/generations`, `GET …/progress` | ✅ |
| Clarifications | `GET /projects/{id}/clarifications`, `POST …/{cid}/answer`, `POST …/{cid}/assume` | ✅ |
| Revisions | `POST /projects/{id}/revisions` | ✅ |
| Chat | `POST /projects/{id}/threads`, `GET/POST /threads/{id}/messages`, `GET /threads` | ✅ |

**Phase 2 is complete: a whole working sandbox can be built without touching SQL.**
**Phase 3 adds the other way to build one: describe it.**

### Generations

**`POST /projects/{id}/generations`** returns **202**, not 201 — nothing is built yet. A
generation takes minutes, so the request enqueues a chain and returns; the console polls
`GET` below, and the *project's own status* is what says whether the sandbox is worth calling.

```json
{"product": "Stripe's card API", "docs": "…", "docsUrl": "…", "agentResearchOnly": true}
```

`docs` is **recommended, never required** (ADR-0010).

**An OpenAPI/Swagger document or a Postman collection pasted into `docs` is read directly.**
Research is skipped entirely, and so is the spec model call — the specification *is* the API, and
asking a model to restate it would cost money to produce something less accurate. What remains is
seeding, which still needs a model because a spec says what a field is, not what a plausible value
looks like.

| | |
| --- | --- |
| Read | OpenAPI 3.x, Swagger 2.0, Postman collection v2 — **JSON only** |
| Not read | YAML. It needs another dependency, and a parser that mangles anchors would be worse than falling back |
| Not followed | a `$ref` to a URL or another file. Local `#/components/schemas/…` refs *are* resolved — they are in the document you pasted |

Anything unrecognised, or a document that yields no usable routes, falls back to the normal
research path and costs nothing extra. Nothing is fetched: paste the document, do not link it. With neither `docs` nor
`agentResearchOnly`, the job fails asking for one — absent and *declined* are different
requests, and collapsing them would hand every caller the least accurate path.

Refused with **409** if a generation is already running for the project, or if the project
already has endpoints. The second is also checked by SPEC, but only after research has been
paid for; failing at the HTTP call is the difference between a clear 409 and a surprise on the
bill.

While the chain runs the project is `GENERATING` and **the sandbox does not serve** — routes
and data appear together rather than one endpoint at a time. It ends `READY`, or `FAILED` if a
step gave up.

**`GET /projects/{id}/generations`** is the history: one row per step, newest first, with
`attempt`, `errorCode` and a message that is always ours and never the provider's.

**`GET /projects/{id}/generations/progress`** answers *how much longer*:

```json
{"waitingForYou": false, "openQuestions": 0, "stepsRemaining": 4,
 "estimatedSeconds": 180, "message": "Building your sandbox — about 3 minutes."}
```

The estimate is **configured, not measured** — there are no real timings yet, and a made-up
average presented as data is worse than an honest constant ops can correct with one `UPDATE`.
Before the spec exists the seed steps are assumed; after it they are counted, so the estimate
gets more truthful as the generation proceeds.

When `waitingForYou` is true there is **no estimate at all**. The clock is not running, and a
countdown to nothing is worse than nothing.

### Chat — the front door

**`POST /threads/{id}/messages`** takes `{"message": "…"}` and returns **202** with the
assistant's *acknowledgement*, not the result.

The same sentence does different things depending on the project: against an empty one it starts
a generation, against a built one it revises the data. Making a user pick the right endpoint for
their sentence would be asking them to know something about the pipeline.

The reply carries the wait time. The transcript then fills in on its own at the few moments a
person cares about — a question was raised, the sandbox is ready (with its base URL), a revision
applied, or it failed. The rest of a generation's five or six steps stay in
`GET …/generations`, where a progress log belongs.

Messages are ordered by **`seq`, not `createdAt`** — a question and the answer to it can land in
the same millisecond.

A project driven through the REST routes has no thread and simply gets no narration.

### Revisions — changing a sandbox that exists

**`POST /projects/{id}/revisions`** takes `{"instruction": "make five customers' cards blocked"}`
and returns **202**. Asynchronous for the same reason a generation is: it is a model call.

**The sandbox keeps serving throughout.** The change lands in one transaction, so a caller sees
the state before or the state after and never half of one — taking a working sandbox offline to
adjust five records would be a poor trade.

If the instruction is ambiguous, **nothing is changed** and questions appear under
`/clarifications`. Answering the last one re-runs the revision with the answers.

Refused with **409** while something else is running for the project, or when the sandbox has no
endpoints yet — there is nothing to revise.

Three things a revision cannot do, by construction rather than by check:

| | |
| --- | --- |
| touch another project, a plan, a quota or `app_config` | the plan the model produces can only name a collection *code*, resolved against the caller's own project. Those are unreachable, not forbidden |
| rewrite a whole collection because the sentence was vague | an `UPDATE`/`DELETE` naming no records is refused |
| apply part of a change | oversized changes are **refused, not clamped**, and the whole plan is one transaction |

### Clarifications — the doubts

Generation **stops** rather than guessing when a request is ambiguous in a way that would change
the sandbox (ADR-0011). Until every question is answered, the sandbox is not built.

**`GET /projects/{id}/clarifications`** — every question ever asked about this project, answered
ones included. They are kept on purpose: three weeks later, *"we assumed `status = BLOCKED`
because you did not say"* is something a user needs to be able to find.

```json
[{"id": "…", "question": "Which field marks a card as blocked?",
  "detail": "A card carries both status and blocked, and they are not the same thing.",
  "subject": {"resource": "cards", "field": "status"},
  "options": [{"id": "opt1", "label": "status = BLOCKED", "detail": "The lifecycle field."},
              {"id": "opt2", "label": "blocked = true",  "detail": "A separate flag."}],
  "allowsAssumption": true, "status": "OPEN"}]
```

**`POST …/{cid}/answer`** takes `{"optionId": "opt1"}` or `{"answer": "free text"}`.

**`POST …/{cid}/assume`** is *"you decide"* — a real answer, not a skip. What was assumed is
recorded and readable afterwards. Refused with 400 on the rare question where a guess would make
the sandbox confidently wrong about the very thing the user asked for.

Answering the **last** open question resumes the generation. Answering an already-answered one
is a 409, so a double click cannot start the chain twice.

### Two error codes with no route yet

Phase 3.1 added `AI_CAPPED` (429) and `AI_UNAVAILABLE` (503) to the catalog. **No endpoint
emits them today** — the spend controls were built before anything user-facing could reach
them. They are listed here so that whoever adds the chat and generation routes uses these
rather than inventing a third:

| Code | Status | Means |
| --- | --- | --- |
| `AI_CAPPED` | 429 | a control worked — the kill switch is off, or a daily cost cap is spent. The caller's existing sandboxes are unaffected |
| `AI_UNAVAILABLE` | 503 | generation is not configured — no active provider, no adapter behind its name, or no API key. Ours to fix, like `AUTH_NOT_CONFIGURED` |

Both return a fixed generic message. The detail that names a variable, a row or a cap goes
to the log only — see ADR-0009.

### A body that will not parse is a 400

Until Phase 3.4 it was a **500**, for every route here: `HttpMessageNotReadableException` had
no handler and fell through to the catch-all. The caller was told the server had broken when
their JSON had. Jackson's own message is logged rather than returned — it quotes the offending
input, and on the generation route that input can be a user's pasted documentation.

⚠️ **Jackson 3 will not map an absent field onto a primitive.** A request record with a
`boolean` component rejects any body that omits it, before validation runs. Box it when absent
is a legitimate way to say "no".

## Notes on what is implemented

**`POST /projects`** returns `baseUrl` — the artifact the user came for. It is
`{publicBaseUrl}/s/{projectId}`: **the sandbox is addressed by the project's own id**, so the
identifier in the console URL and the one in the base URL are the same thing. There is no
separate project key. Manually created
projects are `READY` immediately; generation will create `DRAFT` ones and promote them.

**`POST /projects/{id}/keys`** is the only response that ever carries `key`. Only a hash
and a display prefix are stored, so it cannot be produced again — the response includes an
explicit warning for the console to surface.

**`DELETE /projects/{id}`** archives. The sandbox stops serving; records and request log
survive.

**`POST .../records`** accepts `{"record": {…}}` or `{"records": [ … ]}`, up to 1000 per
request. Quota is checked **once for the whole batch before any insert**, so an over-quota
seed writes nothing rather than leaving a half-loaded collection.

**`PATCH .../records/{recordKey}`** is a shallow merge, and the key field is restored
afterwards — a body cannot silently re-identify an existing record.

**`POST /endpoints`** stores `pathTemplate` **verbatim** — the imitated product's path,
casing included. The only rule is that it starts with `/`. `apiGroup` names a group and
creates it if absent; `apiGroupId` targets one explicitly. A data-backed `behavior`
requires a `dataCollectionId` **in the same project**, checked before the database's own
composite key would catch it, so a cross-project reference is a clean 400.

The response exposes `specificity` read-only. It is a generated column and explains *why*
one route wins over another — otherwise invisible, and it looks like a bug to whoever hits
it.

**`GET /requests`** is paged by **keyset** (`before` = the previous page's `nextCursor`),
not offset: this is the fastest-growing table in the system and `OFFSET 10000` makes the
database walk ten thousand rows to throw them away. `unmatchedOnly=true` is the debugging
view — a call nothing served usually means the spec has a path the real product does not.

## Invariants for whoever builds it

1. **Entitlements are server-authoritative.** The console never computes or enforces a
   limit. A client that decides its own limit is a client that can be edited.
2. **A project API key is returned once**, at creation, and never stored — only
   `key_hash` and `key_prefix`. Drovi *cannot* redisplay it, by design.
3. **Never trust an id from a path or body.** Resolve a project *with* the caller's
   `account_id`, or one user reads another's sandbox.
4. **Entities never cross the boundary.** DTOs are `record`s.
5. **Errors leave through one `@RestControllerAdvice`** with stable codes. 5xx bodies carry
   a generic message and a correlation id — never internals.
6. **The response cannot distinguish "does not exist" from "not yours."**
7. **Quota is checked before a write**, for the whole batch in a bulk seed.
8. **`/s/**` is excluded from Firebase auth**, explicitly. Subjecting it to console auth
   would break every user's integration at once.

## Types

The console generates its TypeScript types from this API's OpenAPI document. It is
generated, never hand-written, so it cannot drift.
