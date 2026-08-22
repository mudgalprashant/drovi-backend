---
title: Operations
status: current
last_updated: 2026-08-23
---

# Operations

Most operational material lives in the **`dev-ops`** repo (branch `drovi`), because it
spans repos and is owned by whoever runs the infrastructure.

| Topic | Where |
| --- | --- |
| Deployment topology and how a deploy happens | `dev-ops/docs/deployment.md` |
| Incidents, rollback, secret rotation | `dev-ops/docs/runbook.md` |
| What to watch, and tracing one sandbox call | `dev-ops/docs/observability.md` |
| Every env var and how to verify it | `dev-ops/docs/env-matrix.md` |
| What a human must set up by hand | `dev-ops/docs/HUMAN-SETUP-CHECKLIST.md` |
| Free-tier limits and what runs out first | `dev-ops/docs/free-tier-budget.md` |

## The two incidents most likely to happen first

Both have step-by-step procedures in the runbook. Both are controlled by **database rows**,
so you can act without waiting for a build.

### Model spend running away

```sql
UPDATE app_config SET value = 'false' WHERE key = 'ai.enabled';
```

Stops *spending*, never *serving* — existing sandboxes keep answering. The value is cached
in-process for up to 10 minutes; restart from Render if the running instance has not picked
it up.

### The database filling up

Almost certainly `mock_request_log` — one row per served call, and **its retention purge is
a Phase 5 deliverable that does not exist yet**. Until it does, this is the most likely
cause of the first storage surprise.

## Health

`/actuator/health` only. `env` and `heapdump` are never exposed — `env` prints
configuration and heap dumps contain secrets and user data.

## What operations does not exist yet

Alerting, structured logging with correlation ids, the retention purge, and the stuck-job
sweeper. All are Phase 5.
