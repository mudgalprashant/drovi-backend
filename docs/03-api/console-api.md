---
title: The console API
status: NOT BUILT
last_updated: 2026-08-23
---

# The console API

⚠️ **Nothing here exists.** No controller outside `MockController`, and **no
authentication filter runs** — which is precisely why no console route has been added yet:
it would be public to the internet.

This document is the specification to build against in Phases 1–2, not a description of
what is there.

## Shape

Base `/api/v1`. JSON. Firebase ID tokens. URLs lowercase, plural, hyphenated; JSON
`camelCase`; timestamps ISO-8601 UTC.

## Required surface

| Area | Endpoints | Phase |
| --- | --- | --- |
| Identity | `GET /me`, `GET /me/entitlements` | 1 |
| Projects | `POST/GET /projects`, `GET/PATCH/DELETE /projects/{id}` | 2 |
| API keys | `POST /projects/{id}/keys`, `DELETE /projects/{id}/keys/{keyId}` | 2 |
| Data collections | CRUD + **bulk seed** | 2 |
| Records | CRUD, paged, filterable | 2 |
| Spec | read API groups, endpoints, schemas | 2 |
| Rules | create, reorder, enable/disable, one-shot | 2 |
| Inspector | `GET /projects/{id}/requests` — keyset paged | 2 |
| Chat | threads, messages, generation status | 3 |

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
