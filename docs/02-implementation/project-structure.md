---
title: Project structure
status: current
last_updated: 2026-08-23
---

# Project structure

```
drovi-backend/
├── build.gradle                 Boot 4, Java 25 toolchain
├── Dockerfile                   THE deploy path, not a portability hatch
├── render.yaml                  the service definition, reviewable in git
├── docs/                        this documentation
└── src/
    ├── main/java/com/pm/drovi_backend/
    │   ├── DroviBackendApplication.java
    │   ├── config/              AppConfigService — the app_config table, cached
    │   ├── domain/              entities (runtime tables only)
    │   ├── repo/                Spring Data repositories
    │   ├── runtime/             the mock server — no servlet types in here
    │   └── web/                 MockController, MockRequestLogger
    ├── main/resources/
    │   ├── application.yaml
    │   └── db/migration/        V1__baseline.sql, V2__seed_catalog.sql
    └── test/java/com/pm/drovi_backend/
        ├── SchemaInvariantsTest.java    guards the four schema invariants
        ├── SandboxRuntimeTest.java      drives whole sandboxes over HTTP
        └── support/PostgresTestBase.java
```

## Where things go

| Thing | Location |
| --- | --- |
| A new runtime behavior | `ApiEndpoint.Behavior` + a branch in `SandboxRuntime.serveFromData` + the CHECK constraint |
| A new rule matcher section | `RuleEngine.matches` |
| A new envelope placeholder | the `values` map in `SandboxRuntime` — `TemplateRenderer` needs no change |
| A new tunable | an `app_config` row in a migration + one `config.getInt/getBoolean` call |
| Anything Anthropic-shaped | a future `integration/anthropic/` package, and nowhere else |
| A schema change | a new `db/migration/V<n>__<desc>.sql` |

## Not every table has an entity

Five of the eighteen tables have entities — the runtime path. The rest are reached with
`JdbcTemplate` or not at all. **Add an entity only when something needs one.**

## Load-bearing names

Renaming either of these breaks something at runtime that no compiler will catch:

| Name | Must match |
| --- | --- |
| `ai_provider_config.adapter_bean` | the Spring bean name of the provider adapter |
| `app_config` keys (`runtime.*`, `ai.*`) | the string literals that read them |
