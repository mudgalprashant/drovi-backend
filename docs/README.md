---
title: Drovi backend documentation
status: current
last_updated: 2026-08-23
---

# Documentation

Human documentation for the Drovi backend. Compressed, machine-oriented context for coding
agents lives in the `global-context` repo (branch `drovi`) — the two are **paired**, and
the same change updates both.

## Start here

| If you want | Read |
| --- | --- |
| What Drovi is | [00-overview/product-brief.md](00-overview/product-brief.md) |
| Where it is going | [00-overview/roadmap.md](00-overview/roadmap.md) |
| How to build the next thing | [02-implementation/implementation-plan.md](02-implementation/implementation-plan.md) |
| Why it is shaped this way | [00-overview/decisions/](00-overview/decisions/) |

## Contents

| Folder | Contains |
| --- | --- |
| [00-overview/](00-overview/) | product brief, roadmap, glossary, ADRs |
| [01-architecture/](01-architecture/) | high-level design, tech stack |
| [02-implementation/](02-implementation/) | coding standards, project structure, implementation plan |
| [03-api/](03-api/) | the sandbox surface and the console API |
| [04-data/](04-data/) | the schema and its invariants |
| [05-security/](05-security/) | **read before touching a route, dependency, env var or grant** |
| [06-operations/](06-operations/) | deployment, runbook, observability (mostly in `dev-ops`) |
| [09-testing/](09-testing/) | testing strategy |

## The one idea

**A sandbox is data, not scripts.** An endpoint is *bound* to a collection of `jsonb`
records and serves them, so "give me five customers whose card was blocked in the last 30
days" is an `INSERT` — not a code change, not a redeploy.

If a proposed change would move behaviour from data into code, it is probably wrong. Say
so rather than implementing it.

## Rules for changing these docs

- The **code is the final authority.** Where a doc disagrees with the code, the code wins —
  fix the doc in the same change.
- A contract change updates `03-api/`, the `global-context` context folder, and
  `shared/api-contract.md` together. Divergence is a defect.
- State facts, not aspirations. If something is not built, say so.
