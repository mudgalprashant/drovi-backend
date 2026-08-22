# ADR-0004 — The model provider, its model, and its caps are database rows

**Status:** accepted · **Date:** 2026-08-22

## Context

Model inference is the only per-use cost in the stack, and the one that can run away in
minutes. Model ids, prices and providers also change faster than anything else in the
system.

## Decision

`ai_provider_config` names the adapter bean, base URL, model and auth header. Per-purpose
routing lives in `app_config` (`ai.model.RESEARCH`, `…SEED`, …). Pricing is an
effective-dated `model_pricing` table. The kill switch and daily caps are `app_config` rows.

The API key is **not** in the database — the row names the *environment variable* that
holds it.

## Consequences

- Switching provider, model or gateway is an `UPDATE`, not a release.
- Caps and the kill switch can be changed **during an incident at 3am without a deploy**.
  That is the point: an AI product that cannot stop spending on command has no free tier.
- Effective-dated pricing means cost is recorded at the rate in force when the call
  happened, so a later price change never restates a past month. Sonnet 5's introductory
  rate and its standard rate are both already seeded.
- A partial unique index enforces exactly one active provider — a second is not a fallback,
  it is double billing.
- Trap: routing a purpose to a model with **no** `model_pricing` row does not fail; it
  records `cost_micros = 0`. A test guards against it.
- A database backup is never a credential leak.
