---
title: Glossary
status: current
last_updated: 2026-08-23
---

# Glossary

Canonical vocabulary. Every repo uses these terms identically; a synonym introduced
anywhere is a defect.

## The hazard word

**"Collection" means two different things here.** Both exist as tables. Never write a bare
"collection" in code, docs or a UI label — always qualify it.

| Term | Table | Is |
| --- | --- | --- |
| **API group** | `api_collection` | a Postman-style folder of endpoints (Cards, Webhooks) |
| **Data collection** | `sandbox_collection` | a named set of stored records (customers, cards) |

## Core terms

| Term | Meaning |
| --- | --- |
| **Sandbox project** | one replica of somebody else's API; one base URL |
| **Project key** | the public, unguessable half of the base URL (`/s/<key>/…`) |
| **Record** | one row of a caller's pretend production data |
| **Record key** | the id the *caller* uses (`cus_9f2`), extracted from the record |
| **Key field** | which field inside a record supplies the record key |
| **Endpoint** | one route in the replica, holding the real product's path verbatim |
| **Behavior** | how an endpoint produces a body: LIST / GET / CREATE / UPDATE / DELETE / STATIC |
| **Binding** | the link from a data-backed endpoint to the data collection it serves |
| **Envelope** | the response-shape template, with `{{items}}`-style holes |
| **Response rule** | the override layer — a 429, an outage, a one-shot failure |
| **One-shot rule** | a rule consumed as it fires |
| **Runtime** | the component that serves a sandbox call |
| **Generation job** | the async research → spec → seed pipeline |
| **Purpose** | why a model was called: RESEARCH / SPEC / SEED / REVISE / CHAT / TITLE |
| **Ledger** | the per-call record of model tokens and cost |
| **Quota** | a plan's per-project storage ceiling, enforced server-side |
| **Entitlement** | what a plan permits; always server-authoritative |
| **Inspector** | the per-project view of served calls |

## Two callers, never confused

| Who | Calls | Authenticates with |
| --- | --- | --- |
| **The user** | the Drovi console | a Firebase ID token |
| **The user's own code** | their sandbox at `/s/<key>/…` | a project API key |

## Two kinds of error, never confused

| Kind | Produced by | Shape |
| --- | --- | --- |
| **Simulated** | the sandbox, acting as the product | the product's own error shape |
| **Platform** | Drovi itself | `{"error":{"code":…,"message":…}}` |

A sandbox returning 429 because the user asked it to is a **success**.
