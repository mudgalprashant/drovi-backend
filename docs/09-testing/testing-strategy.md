---
title: Testing strategy
status: current
last_updated: 2026-08-23
---

# Testing strategy

## The principle

Test the things that are **expensive when wrong**, not the things that are easy to test.
In this system that means: tenant isolation, quota accounting, spend control, and whether a
sandbox actually behaves like the product it imitates.

## The two suites

### `SchemaInvariantsTest` — the schema's safety properties

Not "does the table exist" tests. Each one asserts a rule that is invisible in the DDL,
easy to undo in a later migration by accident, and **expensive rather than merely wrong**
when it goes:

| Guards | Why it would hurt |
| --- | --- |
| a record cannot join another project's collection | cross-tenant leak — unrecoverable |
| counters track insert/update/delete | quota silently stops working |
| every routed model has a price | spend bills silently at zero |
| exactly one active provider | double billing |
| no secret-shaped column on the provider config | a backup becomes a credential leak |
| no raw key column on `project_api_key` | a leaked backup is a leaked credential |
| literal paths outrank parameterised ones | a real route becomes unreachable |
| a data-backed endpoint always has a collection | a 500 found by a customer |
| the free tier stays usable | a product decision, quietly tightened |

### `SandboxRuntimeTest` — the product, over HTTP

Drives whole sandboxes the way a customer's client would: seed a project, call its base
URL, assert the response is what the real product would have given. Everything goes through
the servlet layer on a real database — the two places where a mapping or a migration is
actually wrong.

## Rules

- **A real Postgres, never H2.** The binary arrives as a Gradle dependency, so this needs
  no Docker and nothing installed. A migration verified against H2 has not been verified.
- **Sandbox behaviour is tested over HTTP**, not by calling the service directly. The
  controller's path decoding and query parsing is where several real bugs live.
- **Reproduce a bug with a test before fixing it.** That test is the deliverable.
- A slice is not done because it compiles. It is done when a test proves the behaviour.

## Traps

| Trap | Reality |
| --- | --- |
| MockMvc `.param(...)` | Sets the parameter map without a query string; the runtime parses `getQueryString()`, so filters and paging silently do nothing. **Put parameters in the URL** |
| `./gradlew test \| tail` | Reports `tail`'s exit code — a failed build looks like a pass. Use `set -o pipefail` or read `build/test-results/test/*.xml` |
| The whole suite failing at once | Almost never your test. `@SpringBootTest` means a context-startup failure takes everything down — usually a mapping mismatch under `ddl-auto: validate`, or a missing bean |

## What is not tested yet

Generation (not built), the console API (not built), rate limiting (not built), and
long-running behaviour such as retention purges. Each arrives with its phase.
