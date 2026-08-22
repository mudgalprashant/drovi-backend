---
title: Branching and workflow
status: current
last_updated: 2026-08-23
authority: dev-ops/docs/branching-strategy.md — canonical. This is the short form.
---

# Branching and workflow

## The five rules

1. **Never commit directly to `main`.** Not a fix, not a typo, not a "quick" revert.
2. **Never open a PR into `main`** except the release PR from `dev`.
3. **All work happens on `feat/<feature-name>` or `fix/<fix-name>`, branched from `dev`.**
4. **PRs are raised into `dev`.**
5. **When `dev` is live and stable, one PR is raised `dev` → `main`, and the human decides
   the merge.** No agent merges to `main`, ever.

```
main                     release. protected. the human alone merges here.
 └── dev                 integration. everything lands via PR + green CI.
      ├── feat/<name>    one vertical slice, short-lived
      └── fix/<name>     one defect, short-lived
```

## Starting work

```bash
git checkout dev && git pull
git checkout -b feat/sandbox-generation      # or fix/<name>
# …work, commit, push…
git push -u origin feat/sandbox-generation
# open a PR into dev
```

**Always branch from an up-to-date `dev`, never from `main`.** Branching from `main` means
your branch is missing everything already integrated, and the PR arrives with conflicts
that are not yours.

## Naming

A branch name states the **slice**, not the file: `feat/sandbox-generation`, not
`feat/add-entity`. A slice that cannot be named in three words is too big — split it.

Match the phase and slice names in
[implementation-plan.md](implementation-plan.md) where one applies, so a branch is
traceable to the plan.

## Before opening a PR

- [ ] Tests written and `./gradlew build` green
- [ ] Rebased on current `dev` (never rebase after review starts — reviewers lose their place)
- [ ] Docs updated in this repo
- [ ] Context updated in `global-context`, `verified` bumped
- [ ] `shared/api-contract.md` updated if either boundary moved
- [ ] [Security checklist](../05-security/security-checklist.md) walked if the change
      touched a route, dependency, env var, DB grant, or config
- [ ] No secret added

## Releasing

When `dev` is genuinely live and stable, open **one** PR `dev` → `main`. Merge commit, then
tag `v0.x.y`. The human decides that merge; it is a release decision, not an engineering
one.

A hotfix is still `fix/<name>` off `dev`. If `dev` is too unstable to release from, that is
the problem to fix — not a reason to bypass it.

## Cross-repo changes

Use **the same branch name in every repo** the change touches, and cross-link the PRs. Land
in this order: `global-context` → `dev-ops` → `drovi-backend` → `drovi-frontend`.

`global-context` has no `dev` branch by design — its context must be correct the moment the
code it describes lands, so it is committed to `drovi` alongside the change.

## Enforcement

⚠️ These rules are convention until GitHub branch protection enforces them. See the
canonical doc for the settings to configure. **Convention is not a control.**
