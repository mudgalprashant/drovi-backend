---
title: The sandbox surface
status: implemented
last_updated: 2026-08-23
---

# The sandbox surface

The public face of every sandbox. `SandboxRuntime.handle` is the authority;
`SandboxRuntimeTest` is the executable contract.

## Base URL

```
https://<host>/s/<projectKey>/<the real product's path, verbatim>
```

INVARIANT: **the caller changes the base URL and nothing else.** Path casing, placeholder
segments, trailing slashes, query parameters and body shapes are the imitated product's,
not Drovi's. Any rule that would rewrite a path breaks the product's only promise.

INVARIANT: `/s/{projectKey}` is the only prefix Drovi owns. **Never add a magic path
inside that space** — it will collide with a real product's route eventually.

## Authentication

Per `sandbox_project.auth_mode`:

| Mode | Header | Secret taken from |
| --- | --- | --- |
| `NONE` | — | the project key alone guards the sandbox |
| `BEARER` | `Authorization: Bearer <key>` | after the prefix |
| `HEADER_KEY` | a configured header name | the whole value |
| `BASIC` | `Authorization: Basic …` | the password half |

The sandbox rejects an unauthenticated call **exactly like the product it imitates**. A
replica that waves everything through means the caller's own auth path is never exercised
until production.

## Behaviours

| Behavior | Does |
| --- | --- |
| `LIST` | filtered, paged read from the bound data collection |
| `GET` | one record by the path parameter named in `key_param` |
| `CREATE` | insert; key from the body or generated, written back into the payload |
| `UPDATE` | shallow merge; the key field is restored so a body cannot re-identify a record |
| `DELETE` | remove; 204 unless the endpoint declares otherwise |
| `STATIC` | render the envelope with no data |

## Paging

`limit` / `page_size` / `per_page`, or `page`, or `offset`. Clamped to
`runtime.max.page.size`; default from `runtime.default.page.size`.

## Filtering

Query parameters become a `jsonb` containment filter — **but only those the data
collection's `record_schema` declares as properties.**

Real products carry query parameters that are not fields (`expand`, `include`,
cache-busters). Treating those as filters would return an empty list for a call that should
have succeeded.

## Two kinds of failure — the boundary invariant

| Kind | Produced by | Shape |
| --- | --- | --- |
| **Simulated** | the sandbox, acting as the product | the product's own error shape |
| **Platform** | Drovi itself | `{"error":{"code":…,"message":…}}` |

A sandbox returning 429 because the user asked it to is a **success**.

### Platform status codes

| Status | Code | When |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | missing or invalid project API key |
| 404 | `SANDBOX_NOT_FOUND` | unknown key, **or** project not `READY` — identical for both, so the response cannot confirm which keys exist |
| 404 | `NOT_FOUND` | no route matched, or no such record |
| 409 | `ALREADY_EXISTS` | create with a key already present |
| 502 | `SANDBOX_MISCONFIGURED` | endpoint bound to a deleted collection, or no key parameter |
| 507 | `QUOTA_EXCEEDED` | plan storage limit. Not 400 — nothing is wrong with the request |

### ⚠️ Known fidelity gap

These wear **Drovi's** error shape, not the imitated product's. A caller's error-handling
path therefore sees something the real product never sends.

The fix is a per-project error envelope the generator fills in — **Phase 5, slice 5.4**.
Until then, do not paper over it by hardcoding one product's error shape.

## Other behaviour

- Trailing slashes are normalised — a formatting difference, not a different resource.
- `sandbox_project.latency_ms` is applied to every response, so a caller can reproduce the
  real product's latency without editing each endpoint.
- Every call is logged to `mock_request_log`, including unmatched routes — which are the
  most useful rows in the table, because they usually mean the generator invented a path
  the real product does not have.

## Changing this contract

Touches `SandboxRuntime`, `SandboxRuntimeTest`, this file, and
`global-context/shared/api-contract.md` — in the same change.
