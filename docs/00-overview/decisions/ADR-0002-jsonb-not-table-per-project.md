# ADR-0002 — Sandbox data is `jsonb` in shared tables, not a table per project

**Status:** accepted · **Date:** 2026-08-22

## Context

Each project holds its own collections of records with their own shapes. The obvious
reading of "the system creates tables to hold the data" is literal `CREATE TABLE` per
project entity.

## Decision

One `sandbox_record` table, keyed by `(project_id, collection_id, record_key)`, payload in
`jsonb`, indexed with GIN (`jsonb_path_ops`).

## Consequences

Table-per-project demos well and collapses in production:

- **catalog bloat** — thousands of projects × several entities each, in `pg_class`
- **migration hell** — the agent revises a schema and every existing project needs DDL
- **quota accounting** would have to walk `pg_class` to answer "how many bytes does this
  project own"
- **connection and planning overhead** grow with tenant count

`jsonb` + GIN gives the same query power (containment filters hit the index) and makes
quota a single indexed read. Schema-per-project was considered as a middle ground and
rejected for the same catalog and DDL reasons at a smaller constant factor.

- Cost: no per-field database constraints. `record_schema` is advisory, and validation on
  write is a per-project setting — deliberately, because a sandbox whose purpose is
  malformed-payload testing must be allowed to hold a malformed payload.
- Cost: complex relational queries across collections are harder. Nothing needs them yet.
