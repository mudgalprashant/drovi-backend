# ADR-0010 — Documentation is recommended, never required

**Status:** accepted · **Date:** 2026-08-23 · **Resolves open decision M**

## Context

The pipeline's first step has to learn what somebody else's API looks like. Where that
knowledge comes from was left open as decision M — *may the researcher use web search, or
must it be given docs?* — because it changes three things at once: how accurate a generated
sandbox is, what a generation costs, and what position Drovi takes on fetching third-party
documentation.

The product's promise is *name a product, get a base URL*. Requiring documentation changes
that to *paste the docs, get a base URL*, which is a different and much less interesting
product. Requiring nothing produces sandboxes built from recollection, silently.

## Decision

**Always ask for documentation. Never require it.**

| Input | Behaviour |
| --- | --- |
| Documentation supplied | Used as the primary source. It outranks the model's recollection wherever the two disagree |
| No documentation, `agentResearchOnly` set | Runs from the model's own knowledge of the product |
| Neither | **Fails**, asking for one or the other |
| A documentation URL | Recorded as provenance and shown back to the user. **Never fetched** |

**No web search, and no fetching of any kind.** Not ruled out forever, but it is a separate
decision with its own consequences — live pages are untrusted content reaching a loop that
will hold database tools in 3.4, grounded calls cost more against a 15-requests-per-minute
tier, and fetching third-party docs is a posture, not a feature.

### The opt-in is the whole decision

Missing documentation and *declined* documentation are different requests. The first is
someone who has not finished asking; the second is someone who accepted a known trade-off.
A silent fallback collapses them, and the result is that every user gets the least accurate
path without ever being offered the better one — the recommendation becomes decorative.

So the absence of docs is not a mode. It has to be chosen, and the choice is recorded on the
job.

### Confidence is part of the output

Because accuracy is now something a user can trade away, they have to be able to see what
they traded. Research reports a `confidence` and a list of `uncertainties` alongside its
findings, and the model is told to set them honestly — an overconfident guess costs the user
more than an admission does.

That is what makes "recommended" mean something: the console can say *this sandbox was built
from recollection, and here is what to check*, and offer to improve it with docs.

## Consequences

**The headline demo still works.** "Mimic Stripe's card API" produces a sandbox with nothing
pasted, provided the user asks for it that way.

**Supplied documentation is untrusted input**, and the largest by volume in the system. It
reaches the model in the user turn, never in the system instruction, and is truncated to
`ai.research.max.docs.chars` before it is billed. RESEARCH holds no tools and writes to no
project table, so the worst a hostile page achieves at this step is a bad description — which
SPEC has to validate regardless.

**Phase 7's OpenAPI import gets more valuable, not less.** A supplied spec beats both
research paths, and this decision is what makes the difference visible: a sandbox generated
with `confidence: LOW` is an advertisement for importing a spec instead.

**Revisit when** generation quality is measurably poor without docs (the roadmap's named
signal is a high unmatched-route rate), or when the terms of fetching documentation have been
looked at properly.
