# ADR-0003 — Storage counters live on the data collection, maintained by a trigger

**Status:** accepted · **Date:** 2026-08-22

## Context

Storage is the metered resource — the thing that actually costs money per project — so
quota must be enforced on every write. That needs a running total.

Three options: `COUNT(*)` at request time, a counter on `sandbox_project`, or a counter on
`sandbox_collection`.

## Decision

Counters on **`sandbox_collection`**, maintained by an `AFTER INSERT/UPDATE/DELETE` trigger
on `sandbox_record`. Project totals are a `SUM` over the project's handful of collection
rows.

## Consequences

**Why a trigger and not service code:** quota is a safety property. It must hold for a bulk
seed, a cascade delete, and a hand-run `UPDATE` during an incident — all of which bypass
Java. A counter that is only correct when the service remembers is a counter that drifts.

**Why the collection and not the project:** a project-level counter would put every insert
of a ten-thousand-row seed behind one row lock. Bulk seeding is the most common write in
the system, so that is precisely the wrong place to serialise.

- Rule: `record_count` and `stored_bytes` are **never written from application code**.
- `pg_column_size` measures the compressed datum, so `stored_bytes` is storage cost, not
  payload length. Correct for quota; do not present it to users as "bytes of JSON".
- Drift is recoverable by recomputing from `sandbox_record` — the runbook has the query.
