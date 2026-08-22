---
title: Security checklist
status: current
last_updated: 2026-08-23
---

# Security checklist

Walk this before merging anything that touches a **route, a dependency, an env var, a DB
grant, a migration, or configuration**. It takes two minutes and is the cheapest control
in the project.

## Always

- [ ] No secret value added anywhere — including a placeholder that looks real
- [ ] No control weakened to make something work (`sslmode`, a port, a grant, a spend cap)
- [ ] Nothing logged that should not be: keys, tokens, full emails, whole record payloads

## If you added or changed a route

- [ ] It requires authentication, **or** its anonymity is deliberate and stated
- [ ] `/s/**` is still excluded from Firebase auth
- [ ] Ownership is enforced by scoping the query with the caller's `account_id` — not by
      trusting an id from the path or body
- [ ] Errors leave through the shared handler; no stack trace, no upstream message
- [ ] The response cannot distinguish "does not exist" from "not yours"

## If you touched the data layer

- [ ] Every `sandbox_record` query has a `project_id` predicate
- [ ] Queries are parameterised — no concatenated SQL or JPQL
- [ ] Counters (`record_count`, `stored_bytes`) are **not** written from Java
- [ ] A write path checks quota **before** inserting
- [ ] The migration is forward-only, and no applied migration was edited

## If you touched generation or model calls

- [ ] The call is outside any transaction
- [ ] Caps and the kill switch are checked **before** the call
- [ ] An `ai_call` row is written on failure as well as success
- [ ] The model routed to has a `model_pricing` row
- [ ] Researched or user-supplied text is treated as data, never instructions
- [ ] The tool surface cannot reach another project, a plan, a quota, or `app_config`
- [ ] Generated seed data is synthetic — no real names, cards, emails or identifiers

## If you added a dependency

- [ ] Pinned to an exact version; no ranges
- [ ] From `mavenCentral()` (or the npm registry for the console)
- [ ] A Gradle *plugin* got extra scrutiny — it runs arbitrary code at build time

## If you touched CI or infrastructure

- [ ] Actions are SHA-pinned, not tag-referenced
- [ ] Job permissions are least-privilege
- [ ] Production secrets are in a protected Environment, not repo-wide
- [ ] The container still runs as non-root; no secret in a layer
- [ ] Only `/actuator/health` is exposed

## If you added an env var

- [ ] Documented in `dev-ops/docs/env-matrix.md` with a verification step
- [ ] Added to `.env.example` as a **name only**
- [ ] The app fails to start without it — no silent default
- [ ] If it governs spend or runtime behaviour, ask whether it belongs in `app_config`
      instead, so it can be changed during an incident without a deploy

## Before a release

- [ ] `./gradlew build` green
- [ ] Migrations run on an empty database
- [ ] The migration is compatible with the **previously running** version (no blue/green)
- [ ] Nothing in `git diff` looks like a credential
