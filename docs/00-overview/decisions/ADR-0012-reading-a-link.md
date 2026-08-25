# ADR-0012 — Reading a link the user gave us

**Status:** accepted · **Date:** 2026-08-25 · **Amends ADR-0010**, which said nothing is fetched

## Context

The product goal has always said a user may supply *"a spec / API collection / sandbox url"*.
The first two were built by pasting. The third was not, because it requires the server to
retrieve a URL a stranger chose — and ADR-0010 deliberately declined to do that, recording it as
a decision to be taken rather than a function to be written.

It has now been taken: **read the link.**

## Why this needed a decision rather than a function

A server that fetches a user-supplied URL is a request-forgery engine aimed at its own network.
The classic targets are all reachable from inside a container that can make outbound calls:

- the cloud metadata endpoint, which on most providers hands the instance's own credentials to
  anything that asks;
- internal services that trust whatever reaches them, because "it came from inside";
- **this application**, whose own routes are more interesting from the inside than the outside.

None of that is hypothetical or exotic. It is the default outcome of naively calling
`httpClient.get(userSuppliedUrl)`.

## Decision

Fetch, behind `UrlGuard` and `DocumentFetcher`, under these rules:

| Rule | What it stops |
| --- | --- |
| **HTTPS only** | anything on the path rewriting the document we build a sandbox from |
| **Every resolved address must be public** | loopback, link-local (metadata), site-local, unique-local, carrier-grade NAT, `0.0.0.0/8` |
| **All** addresses checked, not the first | a host resolving to one public and one private address |
| **Our own host refused** | reaching our routes from inside the network boundary |
| Redirects followed **by hand**, each hop re-checked, capped | the usual way in: a public host that answers 302 into private space |
| Body read to a byte ceiling and abandoned | a URL that streams forever — a one-request denial of service |
| Timeout | a host that accepts a connection and says nothing, costing a thread |
| No credentials, no cookies | a fetch is an anonymous GET or it is a credential leak |
| `fetch.enabled`, defaulting to **false** in code | a capability reaching out from inside the boundary should be off until switched on |

**An allowlist of shapes, not a blocklist of strings.** Matching hostnames against a list of bad
ones is the version that does not work: `127.0.0.1` has many spellings, and a domain that
resolves to it has more. So the check resolves and judges addresses.

### Two shapes of link

A link *to a specification* is fetched and read. A link *to an API* — the goal's "sandbox url" —
lands on a landing page, so a short list of well-known locations is tried (`/openapi.json`,
`/swagger.json`, `/v3/api-docs`, `/.well-known/openapi.json`).

Probing happens **only when the link has no path of its own**, since a link with one is already
pointing at a document. The list is short deliberately: each entry is a request against somebody
else's server, and a long list of guesses is scanning.

### What comes back that is not a spec

It is treated as documentation and researched, exactly as if pasted. **Fetched text and pasted
text are the same trust level** — arbitrary third-party content — and the existing defence covers
both: it reaches the model in the user turn, never the system instruction, and RESEARCH holds no
tools and writes to no project table.

## What this does not close

**DNS rebinding.** Addresses are validated, and then the HTTP client resolves the host again when
it connects; a hostile resolver can answer differently the second time. Closing it means
connecting to the validated address while carrying the hostname through SNI and certificate
verification by hand.

It is not done. It is written here, in the class, and in the context repo, because an
unmentioned gap is worse than a known one — and the far more common attack, a redirect into
private space, *is* closed.

**Revisit if** fetching is ever pointed at something more sensitive than a public spec, or if
this service moves somewhere with a metadata endpoint reachable without a header.

## Consequences

**`docsUrl` now means "read this"**, where it previously meant "remember where this came from".
The research prompt changed with it: it used to tell the model *"you cannot open it, do not
pretend to have read it"*, which would be a lie about a document we had just fetched.

**Pasted content still wins.** If a user supplies both `docs` and `docsUrl`, the paste is used
and nothing is fetched — they have already given us the authoritative version.

**The URL recorded on the job is the one actually read**, after redirects, not the one typed. If
a sandbox comes out wrong, the question is what we read.

**Tested in two halves, on purpose.** `UrlGuardTest` proves the guard refuses private space,
offline, using IP literals so it cannot be disabled by a CI box without DNS.
`DocumentFetcherTest` runs against a real server on loopback — which the real guard refuses,
correctly — with a permissive stand-in that refuses one marker host, proving the fetcher
*consults* the guard on every hop. Neither test can carry both properties, and there is no
configuration flag that relaxes the real guard.
