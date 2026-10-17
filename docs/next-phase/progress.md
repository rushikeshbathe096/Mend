# Next-phase progress (orchestrator state file)

Re-read before every unit; append after every unit. Phases: 0 bug sweep (`bugfix/pre-m3`), 1 CI (`ci/pipeline`),
2 M3 honest intelligence (`m3/honest-intelligence`). Each phase branch from local `main`; merged `--no-ff` into local
`main` and tagged (`pre-m3-bugfix`, `ci-v1`, `m3-complete`) after a green full suite + review. Never pushed.

Verify commands: backend `cd backend && env -u JWT_SECRET ./mvnw clean test`; frontend
`cd frontend && npm test -- --ci && npm run build && npm run lint`; ai-service `pytest` when touched.

## Standing decisions

- DECISION (setup): the "brief" (§20–§24, §50, §54) is not in the repository. Its §54 design-doc headings are taken from
  `docs/m2/M2-design-doc.md` (Problem … Exit criteria), which earlier milestones wrote to the brief; §50 checklist items
  are derived from the M3 rules in the task prompt and listed in `docs/m3/M3-plan.md`.
- DECISION (setup): `origin/main` is `b669205` and no `pre-m3-clean` tag exists; work continues on local `main`
  (`e591173`), which contains `cleanup/pre-m3`. Nothing is pushed.

## Log

### Phase 0 — pre-M3 bug sweep (branch `bugfix/pre-m3`)

Status at start of the orchestrator run: complete. `docs/audit/bug-sweep-report.md` covers registry + items 1–7 + 6b with
revert proofs; tenant-guard null callers listed with classification (report §1 table).
Commits: `4a49673` registry, `e4247bd` 1, `20b6e3f` 2, `44fc1bd` 3, `2e106e7` 4, `4dd132c` 5, `efdd827` 6, `1461ce6` 6b,
`8cd6ade` 7, `d04e53b` report.
Last recorded runs: backend `[INFO] Tests run: 809, Failures: 0, Errors: 0, Skipped: 0`; frontend lint 0 errors / 1
warning, `Tests: 53 passed`, build OK.
Remaining for the phase: fresh-subagent review of the branch diff, fixes, full verify, merge `--no-ff` + tag `pre-m3-bugfix`.

#### Phase 0 review (fresh subagent; inputs: AGENTS.md, bug-sweep spec, `git diff main..bugfix/pre-m3` minus docs)
No blockers. Findings and dispositions:
1. MAJOR "system-call entry point never used" — REJECTED with reason: no guarded (user-facing) service method has an
   internal caller; the internal write paths (`createReview`, `transitionState`, `logEvent`, …) were never tenant-checked,
   take the merchant from the row they act on, and are called only by system code. Guarding them is beyond the spec
   ("replace the copied membership checks"). `requireSystemAccess` stays the explicit entry for a future internal caller of a
   guarded method. Recorded as DECISION.
2. MINOR substring dedupe in `appendReasonIfPending` — FIXED (whole-entry match) + test + revert proof
   (`substringOfAnExistingEntryIsStillAppended:99 … expected: <2> but was: <1>`).
3. MINOR review code from untrusted text — FIXED (allow-list of MEND codes) + test + revert proof
   (`unknownCodeIsUncoded:106 expected: <UNCODED> but was: <REFUND_NOW>`).
4. MINOR customer summary drops SYNTHETIC — FIXED + test + revert proof
   (`syntheticRecoveryIsReportedSeparately:109 expected: <1> but was: <0>`).
5. MINOR intents deferred before V17 may get up to `maxDeferrals` more deferrals — ACCEPTED, documented here: fixing it
   needs a data update (hard-stop class); deferral only happens for outcomes where nothing reached the provider, so the
   extra deferrals cannot move money; the cap still ends in review.
6. MINOR `transitionState` can still set RECOVERED without attribution — DEFERRED (beyond spec): both MEND paths set
   `MEND` in the same transaction; making `transitionState` refuse RECOVERED changes the reconciler/webhook recovery
   paths. Candidate for M3/M4 hardening.
7–11 NITs: 8 (dead null check) and 9 (await on void reload) FIXED; 7 (404 vs 403 merchant probing, pre-existing), 10
   (bootstrapLogin without memberships, no callers), 11 (first reason unprefixed; `created_at` covers it) ACCEPTED;
   comment form "Bug sweep behaviour change" kept (the AGENTS form names M2/ADR-002).
Commit `fb1f09f`. Verify: backend `[INFO] Tests run: 812, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`;
frontend `npm run lint` exit 0 (`✖ 1 problem (0 errors, 1 warning)`), `Tests: 53 passed, 53 total`, build exit 0.
Next: merge `bugfix/pre-m3` into local `main` (`--no-ff`), tag `pre-m3-bugfix`; then Phase 1 U1.1.
