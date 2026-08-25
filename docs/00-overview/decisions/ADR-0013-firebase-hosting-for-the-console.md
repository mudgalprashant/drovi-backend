# ADR-0013 — Firebase Hosting for the console

**Status:** accepted · **Date:** 2026-08-25 · **Extends ADR-0005**, which named Render or Cloudflare

## Context

ADR-0005 chose Next.js and said the console would be deployed to *Render or Cloudflare, not
Vercel*. That decision's real content was the **exclusion**: Vercel's Hobby plan is
non-commercial only, so a product with a paid tier breaches its terms the day that tier goes
live. Render and Cloudflare were named as two hosts that do not have that problem, not as an
exhaustive list.

Identity is already Firebase. Putting the console on Firebase Hosting keeps sign-in, its
authorized domains and the hosting origin in one console instead of two.

## Decision

**Firebase Hosting, serving a static export.**

Firebase's terms carry no non-commercial restriction, so ADR-0005's objection does not apply.
Render and Cloudflare remain fine; this is not a rejection of either.

### Static export, not App Hosting

Firebase offers both. **App Hosting** runs a Node server and would have worked with no code
change, but:

- The console is **entirely client-rendered.** Every page is a client component, every byte of
  data is fetched in the browser with the user's Firebase token, and the whole thing sits behind
  a login. There is nothing for a server to render.
- App Hosting **requires the Blaze plan.** That cuts against how the rest of this stack was
  chosen — Render was picked on billing model rather than specs (#28: no card, and it suspends
  rather than bills).

Paying for a Node process to render nothing is the wrong trade. Static files on a CDN have no
cold start, which for a free-tier project is a real improvement over the backend's own behaviour.

## Consequences

**No dynamic route segments.** A static export cannot serve `/projects/[projectId]` — Next needs
every id at build time and project ids do not exist yet. The console uses `/project?id=…`. The
page is behind a login, so nobody is sharing or indexing the URL.

**`cleanUrls` is load-bearing**, and `firebase init` deletes it. Without it `/project` returns the
404 page, because Firebase does not try `<path>.html` on its own. There is a comment inside
`firebase.json` saying so, because the next `firebase init` will strip it again.

**The origin list grows.** `DROVI_CONSOLE_ORIGINS` on the backend must include the hosting
domain, or every console request fails at the preflight with a browser error that says nothing
about the API.

**Hosting is now split across two providers** — backend on Render, console on Firebase. That is
one more dashboard, and one more place a domain has to be registered when it changes. Accepted:
the alternative is moving identity or the backend, and neither is worth the churn.

**If the console ever needs a server** — server-side rendering for a public page, a route that
holds a secret — this decision has to be revisited rather than worked around. Reaching for an
API route in a static export does not fail loudly; it silently does not exist.
