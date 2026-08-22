---
title: Product brief
status: current
last_updated: 2026-08-23
---

# Product brief

## The problem

You are integrating a third-party API. One of these is true, and usually several:

- it is **paid**, and you are burning credits on development traffic
- its **free tier is crippled** — a handful of calls, or a subset of endpoints
- its "sandbox" is a **different service** with a different URL and different behaviour,
  so what you test is not what you ship against
- it only offers **production data**, which you should not be developing against
- the edge cases you need — a blocked card, a declined payment, a rate limit, a timeout —
  are **impossible to produce on demand**

The last one is the sharpest. The bugs that reach production are in the paths you could
never trigger locally.

## What Drovi does

You describe the product in chat. An agent researches its real API surface, generates the
endpoints, schemas and realistic data, and hands you a base URL:

```
https://api.drovi.dev/s/<project-key>
```

Paste that over the production base URL. **Nothing else in your code changes** — same
paths, same payload shapes, same auth header, same error semantics.

Then you steer it from the same chat:

> give me five customer IDs whose card was blocked in the last 30 days
>
> make `POST /v1/charges` return a 429 for the next two calls
>
> add 800ms of latency so I can see my timeout handling work

## Why the base URL is the whole product

Every design decision follows from one promise: **swap the base URL, change nothing else.**

That is why `path_template` stores the imitated product's path verbatim, why the runtime is
a catch-all rather than generated routes, why the sandbox rejects unauthenticated calls
exactly like the real product, and why response shapes are the product's rather than ours.

Anything that erodes that promise — normalising a path, imposing a house error format,
requiring a client-side SDK — is a defect, however convenient.

## The central design idea

**A sandbox is data, not scripts.**

An endpoint is *bound* to a collection of `jsonb` records and serves them. So "five
customers with blocked cards" is an `INSERT`, and behaviour changes in a second without a
deploy. Rules are a thin override layer on top, for what data cannot express: a 429, an
outage, a call that fails exactly once.

This is what makes the chat able to change a sandbox's behaviour as fast as you can ask.

## Who it is for

Developers integrating a paid or awkward third-party API — payments, cards, KYC, logistics,
telecom — who need to build and test against realistic behaviour without spending money or
touching production data.

## Business model

Freemium. Storage is the metered resource, because it is what actually costs us money per
project. The free tier is deliberately **usable** — two real sandboxes, not an expiring
demo. A developer who cannot finish one integration on the free tier never reaches a paid
one.

## What it is not

| Not | Why |
| --- | --- |
| A recording proxy | We generate from research, not from captured traffic. No production credentials involved |
| A contract-testing tool | Adjacent, and complementary. Drovi is for *developing* against an API, not for verifying a contract in CI |
| A general API mocking library | Those need you to write the mocks. The point here is that you do not |
| A load-testing target | The free instance is small; realism is the goal, throughput is not |
