# ADR-0005 — The console is Next.js + React + TypeScript

**Status:** accepted · **Date:** 2026-08-23

> **Extended by ADR-0013:** the console is hosted on **Firebase Hosting** as a static
> export. This ADR's real content was excluding Vercel on terms; Firebase carries no
> such restriction, and Render and Cloudflare remain fine.

## Context

Drovi's client was previously specified as a React Native + Expo mobile app, for an
unrelated product. Drovi is a developer tool: its users are at a keyboard, next to an IDE,
pasting a base URL into a codebase. A mobile-only client is not a plausible shape.

## Decision

A web console built with **Next.js, React and TypeScript**.

## Consequences

- Chat is the primary surface and generation takes minutes, so streaming matters; the
  React ecosystem has the strongest support for it.
- The console is a code-and-JSON-heavy UI — schema viewers, record editors, request logs.
  That ecosystem is deepest in React.
- One language across the console and its tooling; API types are **generated** from the
  console API's OpenAPI rather than hand-written, so they cannot drift.
- **Hosting is constrained.** Vercel's Hobby plan is non-commercial only, so a paid tier
  would breach its terms. The console deploys to Render or Cloudflare instead. This is a
  terms constraint, not a capability one — recorded so Vercel's free tier does not tempt a
  re-proposal.
- Cost: SSR is largely unnecessary for an authenticated tool with no SEO needs. Accepted —
  Next.js is chosen for the ecosystem and streaming story, not for rendering strategy.
- Nothing in the console is a security boundary. Entitlements are read from the server and
  never computed locally.
