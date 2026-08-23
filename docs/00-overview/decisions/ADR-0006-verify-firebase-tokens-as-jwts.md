# ADR-0006 — Verify Firebase ID tokens as JWTs, not with the Admin SDK

**Status:** accepted · **Date:** 2026-08-23

## Context

Firebase owns identity: the backend verifies ID tokens and never issues them. The obvious
implementation is the Firebase Admin SDK's `verifyIdToken`.

But a Firebase ID token is a standard **RS256 JWT**. Google publishes the signing keys as a
JWK set, and the token carries a normal `iss`/`aud`/`exp` claim set.

## Decision

Verify with Spring Security's OAuth2 resource server against Firebase's JWK set:

- decoder: `NimbusJwtDecoder.withJwkSetUri(…securetoken@system.gserviceaccount.com)`
- issuer: `https://securetoken.google.com/<project-id>`
- audience: `<project-id>`

The Admin SDK is not a dependency.

## Consequences

**The operational win is the real reason.** Verification now needs only the **project id** —
a non-secret. No service-account JSON, no `DROVI_FIREBASE_CREDENTIALS_B64`, and therefore
one fewer top-tier credential to store, rotate and leak. A database backup or a config dump
cannot contain it because it does not exist.

- Weight: the Admin SDK pulls grpc and google-cloud transitively, which is real memory on a
  512 MB instance. The resource server is already part of Spring Security.
- **No crypto is hand-rolled.** Signature verification, key rotation, caching and clock skew
  are Nimbus and Spring Security's, both long-established.
- ⚠️ **The audience check is not optional.** Firebase's signing keys are shared across every
  project, so without validating `aud` a valid token minted for *any other* Firebase project
  would verify against ours. That single check is what makes this safe, and it is covered by
  `SecurityConfig.audienceValidator`.
- Cost: if we ever need user *management* — disabling a user, minting custom claims — that
  does require the Admin SDK. Nothing needs it today, and adding it later changes nothing
  about verification.
- The unconfigured case fails closed: with no project id there is no `JwtDecoder`, and every
  console route returns `AUTH_NOT_CONFIGURED`. Guarded by `AuthNotConfiguredTest`.
