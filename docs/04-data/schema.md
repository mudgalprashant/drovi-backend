---
title: The schema
status: implemented
last_updated: 2026-08-23
---

# The schema

**`src/main/resources/db/migration/V1__baseline.sql` is the authority**, and it reads as
the design document — its header states the four invariants everything else follows from.
Read it before this file. 18 tables, verified against a real Postgres on every test run.

## The four invariants

1. **A sandbox is data, not scripts.** Endpoints bind to a data collection; rules are an
   override layer (ADR-0001).
2. **Storage is the metered resource, so every byte is attributed on write** — by a
   trigger, because a bulk seed or a hand-run `UPDATE` bypasses Java (ADR-0003).
3. **Model spend is ledgered, and its caps live in the database** (ADR-0004).
4. **A project can never read another project's rows** — enforced by composite foreign
   keys, not by discipline.

Each is guarded by a test in `SchemaInvariantsTest`.

## Tables

| Group | Tables |
| --- | --- |
| Runtime config | `app_config`, `ai_provider_config`, `model_pricing` |
| Identity & plans | `plan_catalog`, `accounts`, `account_usage_month` |
| Projects | `sandbox_project`, `project_api_key` |
| **Data store** | `sandbox_collection`, `sandbox_record` |
| Spec | `api_collection`, `api_endpoint`, `response_rule` |
| Generation & chat | `chat_thread`, `chat_message`, `generation_job`, `ai_call` |
| Observability | `mock_request_log` |

## Indexes that are correctness, not performance

Do not drop these to speed up writes.

| Index / constraint | Guarantees |
| --- | --- |
| `sandbox_record_tenant_fk` | a record cannot join another project's data collection |
| `api_endpoint_collection_fk`, `response_rule_endpoint_fk` | the same, for the spec tables |
| `sandbox_project_key_uk` | one sandbox per base URL |
| `sandbox_record_key_uk` | one record per key within a data collection |
| `project_api_key_hash_uk` | a key hash identifies exactly one key |
| `ai_provider_config_single_active_uk` | one active provider — a second is double billing |
| `api_endpoint_route_uk` | one endpoint per (project, method, path) |
| `api_endpoint_binding_ck` | a data-backed endpoint always has a collection to read |

## The generated column

`api_endpoint.specificity` = segments minus placeholders, `GENERATED ALWAYS … STORED`. It
decides which template wins when two match, generated precisely so no writer can get the
ordering wrong. Read-only in Java.

## The trigger

`sandbox_record_usage()` maintains `sandbox_collection.record_count` and `stored_bytes`.

INVARIANT: **never write those columns from application code.** Project totals are a `SUM`
over a project's handful of collection rows — cheap, and it never contends. See ADR-0003
for why the counters are not on `sandbox_project`.

`pg_column_size` measures the compressed datum, so `stored_bytes` is storage cost, not
payload length. Correct for quota; do not present it to users as "bytes of JSON".

## Query shapes

| Query | Shape |
| --- | --- |
| List records | `data @> CAST(:filter AS jsonb)` — containment, so the GIN index is used |
| Fetch one | the `(collection_id, record_key)` unique index |
| Quota | `SUM` over `sandbox_collection` joined to `plan_catalog` |

`sandbox_record_data_idx` uses `jsonb_path_ops`: half the size of the default opclass and
enough for containment, the only operator the filter builds.

## Conventions

`uuid` PKs for anything a client sees; `bigserial` only for the append-only
`mock_request_log`. `timestamptz` UTC everywhere. Enums are `text` + `CHECK`, never PG enum
types, so ops can evolve a value without a migration. Every table carries
`created_at`/`updated_at`.

## Seed data is configuration

`V2__seed_catalog.sql` uses `ON CONFLICT DO NOTHING`, never `DO UPDATE`, so a redeploy
cannot stamp on an operator's production edit.

⚠️ The `plan_catalog` numbers are **placeholders that looked reasonable**, not a pricing
decision. Phase 6.

## Growth watch

`mock_request_log` grows fastest — one row per served call — and **its retention purge is
not written** (Phase 5.1). Until it is, this table grows without bound.
