# drovi-backend

Drovi builds a working replica of somebody else's production API, so you can
develop against it.

The problem it solves: the third-party API you need to integrate is paid, its
free tier is crippled or returns live production data, and its "sandbox" is a
different service with a different URL that behaves differently from the thing
you actually ship against.

With Drovi you describe the product in chat. An agent researches its real API
surface, generates the endpoints, schemas and realistic data, and hands you a
base URL:

```
https://api.drovi.dev/s/<project-key>
```

Paste that over the production base URL and your integration runs unchanged.
Then you steer it from the same chat:

> give me five customer IDs whose card was blocked in the last 30 days
>
> make `POST /v1/charges` return a 429 for the next two calls

## How it works

| Piece | What it does |
| --- | --- |
| **Generation** | Researches the real product, emits a Postman-like collection of endpoints, schemas and seed data |
| **Data store** | Per-project `jsonb` collections. An endpoint is *bound* to one and serves it |
| **Runtime** | Matches an incoming request to an endpoint, applies any override rules, renders the response |
| **Rules** | The override layer — rate limits, outages, one-shot failures: what data cannot express |
| **Metering** | Storage and model spend, both capped per plan and both enforced server-side |

The design rests on one idea: **a sandbox is data, not scripts.** Asking for
five blocked cards is an `INSERT`, not a code change and not a redeploy. That is
what makes the chat able to change behaviour in a second.

## Status

Pivoted from an unrelated product (a flight-tracking app) on 2026-08-22. That
work is preserved on the `dev` and `feat/p0-foundations` branches; `main` is the
sandbox platform and shares only its build, deployment and test scaffolding.

| Area | State |
| --- | --- |
| Schema | ✅ 18 tables via Flyway, seeded, verified against real Postgres |
| Build / deploy | ✅ Boot 4, Java 25, Docker → Render, embedded-Postgres tests |
| Mock runtime | ✅ routing, rules, data-backed CRUD, auth, quota, request log — 23 tests green |
| Identity | ❌ Firebase token verification not wired; accounts exist only in the schema |
| Generation / chat | ❌ no provider adapter yet — `ai_provider_config` is seeded but inactive |
| Console (web app) | ❌ not started |

The runtime is real: seed a project and it serves. What it cannot yet do is
*generate* one from a chat message — that is the next slice, and it is the one
that needs an API key.

## Running the tests

```
./gradlew test
```

Needs nothing installed. The suite downloads and runs a real PostgreSQL, because
the schema uses partial indexes, generated columns, `jsonb`, composite foreign
keys and a trigger — none of which an in-memory substitute implements. A
migration verified against H2 has not been verified.
