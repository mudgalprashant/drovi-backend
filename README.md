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

## Contributing

**Never commit to `main`, and never open a PR into it** except the release PR from `dev`.

```bash
git checkout dev && git pull
git checkout -b feat/<feature-name>      # or fix/<fix-name>
# …work…  then open a PR into dev
```

`dev` → `main` happens once `dev` is live and stable, and **the human decides that merge**.
Full rules: [docs/02-implementation/branching-and-workflow.md](docs/02-implementation/branching-and-workflow.md).

## Status

| Area | State |
| --- | --- |
| Schema | ✅ 18 tables via Flyway, seeded, verified against real Postgres |
| Build / deploy | ✅ Boot 4, Java 25, Docker → Render, embedded-Postgres tests |
| Mock runtime | ✅ routing, rules, data-backed CRUD, auth, quota, request log |
| Identity | ✅ Firebase ID tokens verified as JWTs; deny by default. Needs `DROVI_FIREBASE_PROJECT_ID` to go live, and fails closed without it |
| Console API | ✅ projects, keys, collections, records, API groups, endpoints, rules, inspector — a whole sandbox with no SQL |
| Model spend controls | ✅ provider adapter, ledger and caps (Phase 3.1). Testable with no API key |
| Generation / chat | ❌ no pipeline, no job runner, no chat — Phase 3.2–3.4 |
| Console (web app) | ❌ not started |

105 tests green against a real embedded Postgres.

The runtime is real: seed a project and it serves. What it cannot yet do is
*generate* one from a chat message — that is the next slice, and it is the one
that needs an API key.

## Running it locally

```bash
export $(grep -v '^#' .env | grep -v '^$' | xargs)   # see .env.example
./gradlew bootRun
```

⚠️ **Spring Boot does not read `.env`.** Copying `.env.example` to `.env` on its own
changes nothing — the values have to reach the process as real environment variables, via
the `export` above, your IDE's run configuration, or `direnv`.

### Turning identity on

Console routes (`/api/v1/**`) need exactly one variable:

```bash
export DROVI_FIREBASE_PROJECT_ID=your-firebase-project-id
```

Find it in the Firebase console under **Project settings → General → Project ID**. It is
**not a secret**, and no service-account key is needed — Firebase ID tokens are ordinary
RS256 JWTs verified against Google's published keys
([ADR-0006](docs/00-overview/decisions/ADR-0006-verify-firebase-tokens-as-jwts.md)).

| State | What the console does | What sandboxes do |
| --- | --- | --- |
| unset | every `/api/v1/**` route → `503 AUTH_NOT_CONFIGURED` | keep working |
| set | `401` without a token, `200` with a valid one | keep working |

A **wrong** project id fails quietly: real tokens are rejected as having the wrong
audience, which reads like "my login is broken". Check this value first.

In production it is entered in the **Render dashboard** — `render.yaml` declares it
`sync: false`, meaning "prompt me, never store it in git".

## Running the tests

```
./gradlew test
```

Needs nothing installed. The suite downloads and runs a real PostgreSQL, because
the schema uses partial indexes, generated columns, `jsonb`, composite foreign
keys and a trigger — none of which an in-memory substitute implements. A
migration verified against H2 has not been verified.
