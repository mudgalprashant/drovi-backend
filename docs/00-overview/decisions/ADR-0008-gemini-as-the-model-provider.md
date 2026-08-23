# ADR-0008 — Google Gemini is the model provider

**Status:** accepted · **Date:** 2026-08-23 · **Supersedes the provider choice in ADR-0004**

## Context

Generation is the product's headline feature and the only per-use cost in the stack. The
Anthropic API requires purchased credits before the first call. Google's Gemini API issues
a key with a genuine free tier — no card, no expiry — which matters for a project being
built before it has revenue.

ADR-0004 already made the provider a database row precisely so this could change without
touching code.

## Decision

**Gemini**, configured entirely in `V3__gemini_provider.sql`:

| Column | Value |
| --- | --- |
| `base_url` | `https://generativelanguage.googleapis.com` |
| `auth_header_name` | `x-goog-api-key` — not an Authorization bearer |
| `api_key_env_var` | `DROVI_GEMINI_API_KEY` |
| `model` | `gemini-3.7-flash` |

The Anthropic row stays, **inactive**. Switching back is then an `UPDATE`, not a migration.

## Consequences

**No code changed.** This is ADR-0004 working as intended — the provider, its URL, its
model, its auth header and the name of the env var holding its key are all columns.

**Free tier limits, and what they mean for us:**

| | Free | Notes |
| --- | --- | --- |
| Requests/minute | 15 | ⚠️ generation is bursty — research → spec → seed is many calls in a row |
| Requests/day | 1,500 | comfortable for development |
| Card required | no | the reason for this decision |

15 RPM is the one that will bite. A single sandbox generation may exceed it, so the job
runner needs to pace itself rather than fan out — worth knowing before Phase 3 rather than
discovering it as a 429 storm.

### ⚠️ The free tier trains on your content

Google's terms are explicit: content submitted to the **non-paid** tier, and the responses,
may be used to improve Google's products, and **human reviewers may read it**. The paid
tier does not.

This is not a theoretical concern for Drovi. Generation sends the user's prompts and
whatever product documentation they supply — which for a real customer may be an internal
or unreleased API — to the model. On the free tier that material leaves our control.

**Therefore:** the free tier is for development. **Move to the paid tier before any real
user's content passes through generation**, and say so in the product's terms until then.
This is the same shape of problem as ruling out Vercel: fine on capability, wrong on terms.

### Pricing is recorded even though the free tier bills nothing

`model_pricing` carries Gemini's list prices. Recording zero while on the free tier would
make the spend caps useless as a usage governor and hide the moment the free tier stops
being enough. The ledger answers "what is this costing" *and* "what would this cost".

Google publishes 3.x prices as effective to 2026-12-31 and **doubling on 2027-01-01**. Both
rows are already seeded — which is exactly what `effective_from` was built for, and means a
call made in December is still costed at December's rate afterwards.

### Model ids move fast

Gemini's catalogue turns over quickly and several attractive models are `-preview`. Verify
ids against Google's live docs when building the adapter; a wrong id fails at request time,
not at startup. This is why routing lives in `app_config` — correcting one is an `UPDATE`.
