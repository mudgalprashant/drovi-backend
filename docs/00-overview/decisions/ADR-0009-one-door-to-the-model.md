# ADR-0009 — One door to the model, and caps that are ceilings

**Status:** accepted · **Date:** 2026-08-23 · **Implements** the Phase 3.1 constraint stated
in the roadmap and the implementation plan

## Context

Phase 3 introduces the only component in the system that can spend money on its own. The
roadmap has said since Phase 0 that the adapter and its controls ship together, and this ADR
records how that was actually built — because three questions had to be answered before the
first line, and each of them is easy to get wrong in a way that stays invisible until a bill
arrives.

1. What stops an adapter being called without the controls?
2. What happens when a control's configuration is missing rather than set?
3. Does a cap reserve spend before a call, or check spend already made?

## Decision

### 1. `AiGateway` is the only caller of `AiProvider`

Not a convention — a structural one. `AiProvider` implementations are package-private and
resolved by bean *name* from `ai_provider_config.adapter_bean`, so no other class can inject
one by type. Everything that spends goes through one method, which resolves the provider,
routes the model, asks `SpendGuard`, calls, and ledgers — in that order, always.

The alternative — adapter and guard as separate collaborators each service wires up — was
rejected because it makes the correct sequence a thing every future caller has to remember,
and there will be five or six of them by the end of Phase 3.

`AiGateway.call` also **refuses to run inside a transaction**, throwing rather than warning.
Generation takes minutes, a free-tier pool is five connections, and the symptom of getting
this wrong is the whole application going quiet — nowhere near the code that caused it.

### 2. Every default fails closed

| Missing | Behaviour |
| --- | --- |
| `app_config.ai.enabled` | treated as **false** — generation off |
| `ai.daily.cost.cap.micros` | treated as **0** — everything capped |
| `ai.account.daily.cost.cap.micros` | treated as **0** — everything capped |
| no active `ai_provider_config` row | `AI_UNAVAILABLE`, nothing called |
| adapter bean named by the row does not exist | `AI_UNAVAILABLE` |
| the env var named by `api_key_env_var` is unset | `AI_UNAVAILABLE` |

A deleted or fat-fingered config row must not read as "no limit". The two failure directions
are not symmetric: an over-strict default is noticed within a minute by whoever is testing
generation, and an over-generous one is noticed when the bill arrives.

A missing key in particular must never degrade into an unauthenticated call. That presents
as a provider outage and costs an afternoon before anyone checks the environment.

### 3. Caps are ceilings, not reservations

`SpendGuard` compares **spend already ledgered today** against the cap. A call that starts
under the cap is allowed to finish above it, by at most one call's worth — bounded by
`max_output_tokens`.

Reserving an estimate up front would need a local tokeniser we do not have, would refuse
work on a guess, and would need reconciling against actuals afterwards. The overshoot is
bounded and known; set the cap below what you can afford to lose, not at it.

## Consequences

**Two new error codes.** `AI_CAPPED` (429) means a control worked; `AI_UNAVAILABLE` (503)
means nobody finished the setup. Keeping them distinct is what lets an operator tell "we
stopped it" from "it was never on" without reading logs.

**Both carry two messages.** The caller gets a fixed generic sentence; the operator detail —
which variable, which row, which cap — goes to the log. "Provider GEMINI needs
`DROVI_GEMINI_API_KEY`" is exactly the sentence that makes an operator's afternoon short and
a stranger's reconnaissance easy.

**A refusal is a ledger row.** `CAPPED` rows are written even though nothing was sent. Without
them a quiet day and a capped day look identical in the one table spend is judged from.

**`TIMEOUT` is not a synonym for `ERROR`.** A timed-out call may have been served and billed;
only our half gave up. The job runner retries `ERROR`, and it must not retry a refusal
(`REFUSED`), which would spend money to be told no a second time.

**Provider error text stops at the adapter.** It routinely echoes the prompt back, and the
prompt can hold a user's own material.

**The whole slice is testable with no API key**, against a stub provider registered exactly
as a real one is — a row naming a bean. The stub counts its calls, so "was capped" is
asserted as *nothing was sent* rather than *an exception was thrown*. A cap that throws after
the request leaves has already cost the money it was meant to save.
