# ADR-0011 — Ask rather than guess, and record what was assumed

**Status:** accepted · **Date:** 2026-08-24

## Context

"Give me a blocked card" is ambiguous in a way the person asking cannot see. Three endpoints
may serve cards. A card may carry `status`, `state` and `blocked`, all plausibly meaning it.
The product may distinguish BLOCKED from FROZEN from CANCELLED.

A generator that picks a reading produces a sandbox that *looks* right. The user integrates
against it and finds out later — which is the most expensive kind of wrong available here,
because the cost lands after they have built on it.

## Decision

**A generation step that is unsure raises questions, and the chain stops until they are
answered.** Not a warning, not a note in the result: a hard pause.

| Rule | Reason |
| --- | --- |
| Doubts are rows in `generation_clarification`, not chat messages | a doubt outlives the conversation. "We assumed `status = BLOCKED` because you did not say" is something a user needs to find in three weeks, not scroll a transcript for. Generation also *blocks* on open rows, which a query has to answer rather than infer |
| Answered rows are kept forever | the history is the feature, not a byproduct |
| **"You decide" is a first-class answer** | this is a mock. For most doubts a plausible assumption beats a blocked generation, and a user who does not care should not be made to care |
| What was assumed is **recorded** | an assumption nobody can look up later is indistinguishable from a bug |
| Concrete options, not a blank box | someone shown three choices answers in one click; someone shown a text field does not answer at all |
| Option ids are **ours**, not the model's | a console posts them back, and an id a model invented is one it can invent differently next time |
| Answers ride forward into the next step | a step not told what was decided asks it again |
| Only ask what **changes the result** | every question costs the user a decision. A doubt they would answer with a shrug goes in `uncertainties`, which is shown but not asked |

`allowsAssumption` defaults to true and is false only where a guess would make the sandbox
confidently wrong about the very thing the user asked for.

### Resuming

The generation works out where it paused by asking the database: **the most recent succeeded
job is, by definition, the one whose successors were never enqueued.** Storing "what to do
next" alongside the questions would be a second source of truth that has to survive a retry, a
requeue and a failure.

Answering is idempotent — the update is conditional on `status = 'OPEN'`, so two clicks resolve
it once. That matters because resolving the *last* one is what restarts the generation.

## Consequences

**A generation can now be waiting on a person, and that is a state the API reports**
(`GET …/generations/progress` → `waitingForYou`). While it is, there is deliberately **no time
estimate**: the clock is not running, and counting down to nothing is worse than saying nothing.

**The pipeline talks to `ClarificationStore`, not `ClarificationService`.** The service resumes
the pipeline, so depending on the service from the pipeline is a constructor cycle — which is
exactly what the first wiring produced. Raising a doubt and reading a settled one are
store-level; answering one is not.

**Cost.** Every question is a round trip through a human, and generation is already minutes
long. The prompt is explicit that a question the user would shrug at should not be asked —
if this turns out to over-ask, the fix is the prompt and the `allowsAssumption` default, not
removing the pause.
