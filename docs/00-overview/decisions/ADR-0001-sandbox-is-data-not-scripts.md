# ADR-0001 — A sandbox is data, not scripts

**Status:** accepted · **Date:** 2026-08-22

## Context

A mock API can produce a response two ways: from **scripted rules** (matcher → canned
response) or from **stored data** (the endpoint reads records and serves them).

Most mocking tools take the scripted route. It is simpler to build and demos well.

## Decision

An endpoint is **bound to a data collection** and serves records from it. `response_rule`
exists as a thin override layer for what data cannot express — a 429, an outage, a call
that fails exactly once.

## Consequences

**Why this matters more than it looks:** the product's core interaction is *"give me five
customers whose card was blocked in the last 30 days."* Under a scripted model that is
authoring five canned responses and wiring them to matchers. Under this model it is an
`INSERT` — which is why the chat can change behaviour in a second, and why a sandbox stays
coherent as it grows (records relate to each other; canned responses do not).

It also makes `GET /v1/cards/card_9` and `GET /v1/cards?status=BLOCKED` automatically
consistent, because they read the same rows. Scripted mocks drift apart the moment someone
edits one and not the other.

- Cost: a data-backed endpoint needs a schema and seed data, so generation does more work
  up front.
- Cost: `STATIC` remains as an escape hatch, and reaching for it because seeding is
  inconvenient degrades a sandbox into a pile of canned responses. Watch for it.
- Every design question should be tested against this: *would this move behaviour from
  data into code?*
