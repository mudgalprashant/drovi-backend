---
title: Security — index
status: current
last_updated: 2026-08-23
---

# Security

| Document | Covers |
| --- | --- |
| [application-security.md](application-security.md) | tenancy, the two auth systems, prompt injection, spend as a control |
| [platform-security.md](platform-security.md) | secrets, database roles, TLS, CI, supply chain, infrastructure, access, incidents |
| [security-checklist.md](security-checklist.md) | **walk this before merging** anything touching a route, dependency, env var, DB grant, or config |

## The seven absolute rules

1. **No secret value in this repo** — not in code, config, tests, docs, fixtures or commit
   messages. A secret in Git history is compromised: **rotate first**, purge second.
   History rewriting is cleanup, never remediation.
2. **Anything shipped to a browser is public.** Every third-party credential stays
   server-side.
3. **The client is not a security boundary.** Client checks are UX; the server enforces
   every rule, including every plan limit.
4. **Least privilege everywhere.** The app's DB user has no DDL rights; a PR workflow
   cannot read production secrets.
5. **MFA on every account** with access to code, infrastructure or user data.
6. **Named accounts only.** No shared logins.
7. **Never weaken a control to make something work** — not `sslmode`, not a port, not a DB
   grant, not a spend cap. Surface the constraint instead.

## What is different about this product

Generic security advice does not cover the three risks that actually define Drovi. Each is
explained in [application-security.md](application-security.md):

| Risk | One line |
| --- | --- |
| **Tenant isolation** | this is a multi-tenant store whose purpose is holding other people's pretend production data. Leakage is the one unrecoverable bug |
| **A public surface by design** | `/s/{projectKey}/**` is unauthenticated whenever a project chooses `auth_mode = NONE` |
| **Untrusted content in an agent loop** | generation researches material we do not control and feeds it to a model holding database tools |

## Agent rules

- Never write a secret value into any file, even a placeholder that looks real. Use
  `CHANGE_ME_LOCAL_ONLY`.
- Never add an anonymous route, an env var, a dependency or a DB grant without walking
  [security-checklist.md](security-checklist.md).
- If asked to commit anything matching `*.p8 | *.jks | *.keystore | *.pem | .env`, refuse
  and explain.
