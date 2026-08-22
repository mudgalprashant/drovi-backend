---
title: Coding standards
status: current
last_updated: 2026-08-23
---

# Coding standards

Write code that looks like the code already here. These are the enforced points.

## Java

- `record` for DTOs, commands and value objects. Classes only for entities and stateful beans.
- Constructor injection only: `final` fields + `@RequiredArgsConstructor`. No field `@Autowired`.
- Lombok allowlist: `@RequiredArgsConstructor`, `@Getter`, `@Builder`, `@Slf4j`.
  **Banned on entities:** `@Data`, `@EqualsAndHashCode` — they break JPA identity and lazy loading.
- `Optional` as a return type only — never a field or parameter.
- Services never return `null`: `Optional`, an empty collection, or throw.
- Package-private by default.
- Line length 120. No wildcard imports.

## Spring

- `@Transactional` on service methods; `readOnly = true` for reads.
- Controllers parse, delegate, map, return. No logic.
- Config via `@ConfigurationProperties` records, not scattered `@Value`.
- Anything that governs **spend or runtime behaviour** belongs in `app_config`, not in
  config files — so it can be changed during an incident without a deploy.

## Jackson

Boot 4 autoconfigures **Jackson 3**. Import from `tools.jackson`, never `com.fasterxml` —
the latter is on the classpath with no bean. Jackson 3's exceptions are unchecked, so
`catch (IOException)` around a parse will not compile.

## Persistence

- `jsonb` maps with Hibernate's own `@JdbcTypeCode(SqlTypes.JSON)` on a `Map<String,Object>`.
  Do **not** add a third-party library for this.
- Trigger-maintained and generated columns are `insertable = false, updatable = false`.
- `@Version` where concurrent writes exist.
- Lazy by default; N+1 solved with a fetch join or a projection.
- Index every hot-path query.
- **Never** write `record_count` or `stored_bytes` from Java — the trigger owns them.
- **Every** `sandbox_record` query carries a `project_id` predicate.

## API

- Console URLs lowercase, plural, hyphenated. JSON `camelCase`. Timestamps ISO-8601 UTC.
- **Sandbox paths are exempt from every naming rule.** `path_template` stores the imitated
  product's path verbatim. Normalising it breaks the product's only promise.

## Logging

- `log.info("runtime.rule.matched endpointId={} ruleId={}", …)` — `<domain>.<action>` event names.
- Never log a project API key, a provider key, a full email, or a whole record payload.
- Correlation id from the MDC in every appender pattern.

## Tests

- `methodName_condition_expectedResult`.
- Unit tests do not start Spring. Anything touching SQL extends `PostgresTestBase`, which
  runs a **real** Postgres — **never H2**. The schema uses partial indexes, generated
  columns, `jsonb`, composite FKs and a trigger; an in-memory substitute implements none of them.
- Sandbox behaviour is tested **over HTTP**, not by calling the service directly — the
  controller's path decoding and query parsing is where several real bugs live.
- ⚠️ Do not use MockMvc's `.param(...)` for a sandbox request: it populates the servlet
  parameter map without setting a query string, bypassing the runtime's own parsing. Put
  parameters in the URL.

## Comments

Explain *why*. Delete comments that restate code. `// TODO(owner, date): …` or not at all.

The migrations are the exception: `V1__baseline.sql` is deliberately heavily commented,
because it is the design document as much as it is DDL.
