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
| Spec | read API groups, endpoints, schemas | ❌ Phase 2.4 |
| Rules | create, reorder, enable/disable, one-shot | ❌ Phase 2.4 |
| Inspector | `GET /projects/{id}/requests` — keyset paged | ❌ Phase 2.5 |
| Chat | threads, messages, generation status | ❌ Phase 3 |

⚠️ **The gap that matters today:** a user can create a project and seed data, but cannot
yet wire an endpoint to it — so a console-created sandbox serves 404 for everything until
Phase 2.4 lands or a route is inserted by SQL.

## Notes on what is implemented

**`POST /projects`** returns `baseUrl` — the artifact the user came for. Manually created
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
