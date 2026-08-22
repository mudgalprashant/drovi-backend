---
title: Tech stack
status: current
last_updated: 2026-08-23
---

# Tech stack

| Concern | Choice | Note |
| --- | --- | --- |
| Language | **Java 25** | toolchain auto-provisioned by Gradle's foojay resolver — a fresh machine needs no JDK installed |
| Framework | **Spring Boot 4.0.7** | servlet stack (`spring-boot-starter-webmvc`); do not mix in WebFlux types |
| Build | **Gradle** (Groovy DSL), wrapper committed | |
| Database | **PostgreSQL** (Supabase free) | the only datastore |
| Migrations | **Flyway**, forward-only | `ddl-auto: validate` in every profile |
| Cache | **Caffeine**, in-process | no Redis: one instance, so a network hop buys nothing |
| JSON | **Jackson 3** (`tools.jackson`) | ⚠️ see below |
| Boilerplate | **Lombok**, restricted allowlist | |
| Tests | **JUnit 5** + **zonky embedded-postgres** | a real Postgres binary as a Gradle dependency |
| Deploy | **Docker → Render** | the Dockerfile *is* the deploy path |
| Identity | **Firebase Authentication** | verify only; never issue. **Not yet wired** |
| Model provider | **Anthropic** | resolved from the database. **Not yet wired** |
| Console | **Next.js + React + TypeScript** | ADR-0005. Not started |

## Two Boot 4 traps

Both have already cost time once. Do not rediscover them.

### Autoconfiguration is split per technology

`flyway-core` on the classpath **does not** enable Flyway in Boot 4 — `spring-boot-flyway`
does. Without it, migrations are silently never run and the app boots on an empty schema.
Expect the same pattern for other libraries.

### Jackson 3 is the autoconfigured mapper

Boot 4 autoconfigures `tools.jackson.databind.json.JsonMapper`. Jackson 2
(`com.fasterxml`) is on the classpath for compatibility **with no bean**, so injecting
`com.fasterxml.jackson.databind.ObjectMapper` fails at startup with "no qualifying bean".

Jackson 3's exceptions are also **unchecked**, so `catch (IOException)` around a parse will
not compile.

## Dependency rules

- **No dynamic versions.** A build that can change without a commit is a vulnerability.
- `mavenCentral()` only.
- Gradle *plugins* execute arbitrary code at build time — scrutinise them more than
  libraries.
- Prefer what the framework already provides. `jsonb` maps with Hibernate's own
  `@JdbcTypeCode(SqlTypes.JSON)`; it needs no third-party library.

## Runtime tuning

512 MB and 0.1 CPU is tight for a JVM. The container sets `MaxRAMPercentage=65` (it reads
the cgroup limit, so changing plan size needs no rebuild), `SerialGC` (below ~2 GB, G1's
background threads cost more on 0.1 CPU than its pauses save) and `TieredStopAtLevel=1`
(skips C2 — slower steady state, markedly faster startup, the right trade when CPU is
scarce).

`DROVI_DB_POOL_MAX` is **2** in production. The Supabase free pooler is shared; a big pool
starves every other client of the project.
