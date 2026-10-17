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

### Phase 1 — CI pipeline (branch `ci/pipeline`)

Plan: U1.1 four scripts (migrations via a JUnit test that builds its own empty database, so no Flyway CLI or new
dependency); U1.2 workflow with postgres/redis services and caches; U1.3 gitleaks-action + dependency-review, validated
with actionlint (docker image, not a build dependency) and a YAML parse; U1.4 README section.
Commits: `166777b` scripts, `55661db` workflow + `.gitleaks.toml`, `2ee1ad0` README, `ab9e903` review fixes.

U1.1 local runs (copied from output):
- `JWT_SECRET=$(openssl rand -base64 48) scripts/ci/backend.sh` → exit 0, `[INFO] Tests run: 813, Failures: 0, Errors: 0, Skipped: 0`, 59 s
- `scripts/ci/migrations.sh` → exit 0, `[migrations] fresh database mend_test_…: applied 18 migrations, current version 18, validate OK`
- `scripts/ci/frontend.sh` → exit 0, `✖ 1 problem (0 errors, 1 warning)`, `Tests: 53 passed, 53 total`, `✓ Compiled successfully`
- `scripts/ci/ai-service.sh` → exit 0 (fresh venv from requirements.txt), `51 passed in 1.79s`
- Failure propagation: a temporary broken `V19` → `migrations.sh exit 1` (`relation "no_such_table" does not exist`); removed.
U1.3: actionlint 1.7.7 exit 0; YAML parse OK (jobs backend, migrations, frontend, ai-service, secret-scan,
dependency-review); shellcheck 0.10.0 on the scripts exit 0; gitleaks 8.21.2 over the history: 6 findings, all literal
test placeholders → `.gitleaks.toml` allowlists exactly those values → "118 commits scanned … no leaks found".
U1.4: README "CI" section. **The first real GitHub Actions run happens when the owner pushes: TBD, not claimed.**
Finding (not changed): ai-service tests rewrite two tracked benchmark JSON files when run (restored with git checkout;
those tests go in U2.6).

Review (fresh subagent; AGENTS.md + phase-1 spec + diff): no blockers.
- FIXED: secret-scan job permissions (`pull-requests: read`, comments off); dependency-review `pull-requests: write`
  dropped; concurrency no longer cancels runs on main; backend.sh comment about JWT_SECRET corrected (it does override
  `jwt.secret` in test contexts; no test depends on the value); FreshDatabaseMigrationTest uses Flyway's own view and
  `MigrationVersion` (robust to dotted/gapped versions); README notes dependency-review prerequisites.
- REJECTED: "gitleaks allowlist unverified" — it was run locally with the config over full history (no leaks);
  "every PR runs twice" — the spec asks for push + pull_request; "validation not evidenced" — recorded above.
- Extra fix found while re-verifying: `migrations.sh` now runs `clean test` (a stale `target/classes` kept the removed V19).
Verify after fixes: backend `[INFO] Tests run: 813, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; frontend
`Tests: 53 passed, 53 total`, build OK, lint exit 0; `migrations.sh` → applied 18, validate OK; actionlint exit 0.
Next: merge into local main (`--no-ff`), tag `ci-v1`; Phase 2 U2.0 on `m3/honest-intelligence`.

### Phase 2 — M3 honest intelligence (branch `m3/honest-intelligence` from `main` @ `1c7fb7d`)

#### U2.0 design pack
Plan: inventory every AI call site with file:line; design doc with the M2 headings (standing DECISION); ADR-003 Proposed;
plan with units refined from the inventory and a §50 checklist derived from the M3 rules.
Written: `docs/m3/M3-analysis.md`, `docs/m3/M3-design-doc.md`, `docs/adr/ADR-003-honest-intelligence.md`, `docs/m3/M3-plan.md`.
DECISIONS:
- Taxonomy keyed by Razorpay `error_reason` (documented as programmatically handleable), fallback `error_source`
  (`gateway`/`razorpay` documented transient → GATEWAY_ERROR); `error_code` values are not enumerated in the docs, so not
  used. Source: https://razorpay.com/docs/errors/payments/list/ , https://razorpay.com/docs/errors/ (fetched 2026-10-18).
- Two classes added with evidence: LIMIT_EXCEEDED (documented limit family), INSTRUMENT_INVALID (card_expired,
  debit_instrument_blocked/inactive). Strategy per class = the pre-M3 equivalent class's strategy (no new action type, no
  money-semantics change beyond the spec); UNCLASSIFIED → ineligible like UNKNOWN (fail closed).
- Confidence-gate removal moves from U2.3 into U2.2: a deterministic class has no confidence, so the gates would block
  every campaign once the AI call is gone.
- Reply retention: 90 days stated; the purge job is M5-owned.
Next: U2.1 taxonomy (review of U2.0 runs in parallel).

U2.0 review (fresh subagent; AGENTS.md + U2.0 spec + design pack; file:line spot-checked, most correct):
- BLOCKER 1 (ALREADY_PAID could reach SAFE_RETRY) — FIXED in the design: ALREADY_PAID only brings forward the
  AWAITING_PAYMENT evidence lookup the reconciler runs anyway (that branch never resends; NEEDS_RECONCILIATION untouched;
  later steps keep the reconciler's own schedule); no link intent → review; U2.4 test: a reply never causes a provider
  POST or SAFE_RETRY.
- MAJOR 2 source fallback for present-but-unmapped reasons — FIXED in code (`FailureTaxonomy`: source decides only when
  no reason is sent) + tests; revert proof: old rule → `Tests run: 53, Failures: 7`
  (`unmappedReasonIsUnclassified:85 expected: <UNCLASSIFIED> but was: <GATEWAY_ERROR>`).
- MAJOR 3 compliance would pass UNCLASSIFIED — FIXED in plan/ADR: compliance blocks UNCLASSIFIED + any non-taxonomy class,
  replacing UNKNOWN and LOW_CONFIDENCE blocks (not a weakening); regression tests in U2.2.
- MAJOR 4 legacy rows — DECISION: legacy classes fail closed (treated as UNCLASSIFIED) when re-evaluated; never rewritten.
- MAJOR 5 OPT_OUT framing — FIXED: fail-closed compliance block at new-intent and pre-dispatch checks, audited, human
  clear endpoint; Requirement 2 reworded.
- MAJOR 6 ADR mapping table — FIXED (strategy per class; BANK_DECLINED has no equivalent, documented).
- MAJOR 7 inventory misses — ADDED to analysis + U2.3/U2.6 scope.
- MINOR 8 taxonomy version column — FIXED: `classification_results.taxonomy_version` (V19); `model_version` NULL.
- MINOR 9, 10, 11, 12, NIT 13 — FIXED in docs (transaction wording, extraction of code/reason/source, distinct enum
  name `PaymentFailureClass`, reply-task gaps, header).
Next: commit, then U2.1 review.

#### U2.1 deterministic failure taxonomy
Plan: pure-Java `com.mend.taxonomy` table keyed by `error_reason` (docs-cited), source fallback only without a reason,
test per documented reason written independently of the table, doc-derived fixture, purity guard. Not wired (U2.2).
Commits: `314bf9e` taxonomy + tests, `563ccc3` (source fallback only without a reason; from the U2.0 review),
`0014eed` U2.1 review fixes.
Tests: `FailureTaxonomyTest` `Tests run: 53, Failures: 0`; `TaxonomyPurityTest` 1. Full suite after `0014eed`:
`[INFO] Tests run: 867, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
Review (fresh subagent, fetched the Razorpay page itself: all mapped reasons exist):
- FIXED MAJOR `authorisation_declined_by_psp` → unmapped (PSP declined, not the customer); FIXED MINOR
  `payment_session_expired`, `payment_collect_request_expired` → unmapped (no actor in the docs). Table 40 → 37 reasons.
- FIXED MINOR canonical-constructor normalisation; test gap (customer/business source never decides); purity test now walks
  subpackages and flags fully qualified references; NIT asString; NIT single-URL citation explained in ADR-003.
- ACCEPTED NIT wildcard static import (readability only). Out of scope noted: the doc-derived fixture's `_source` is a
  mintlify mirror of razorpay.com; LIMIT_EXCEEDED→CUSTOMER_ACTION_REQUIRED is the pre-M3 rule (kept, ADR-003 §4).
- Revert proofs: psp decline mapped again → `tableIsFullyTested:74 expected: <37> but was: <38>`,
  `unmappedReasonIsUnclassified:83 expected: <UNCLASSIFIED> but was: <CUSTOMER_DECLINED>`; a qualified
  `org.springframework` reference in the package → `TaxonomyPurityTest.onlyJavaImports:35` fails.
Next: U2.2 (in progress: V19, ClassificationService on the taxonomy, eligibility, strategy).

#### U2.2 AI off the payment hot path
Plan: taxonomy into ClassificationService (provider fields extracted separately), V19 (confidence nullable +
taxonomy_version), eligibility/strategy/compliance by class with confidence gates removed (moved forward from U2.3,
standing DECISION), orchestrator without the agent step, proof test with ai-service unreachable.
Commit `31a1949`. Full suite: `[INFO] Tests run: 887, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
Test changes (count 867 → 887, none deleted):
- AiRecoveryAgentIntegrationTest → DeterministicRecoveryPathIntegrationTest (same 11 scenarios rewritten to the deterministic
  behaviour; own-row cleanup); ClassificationServiceTest 3 rewritten; RazorpayTestModeE2E fixture now carries Razorpay's
  documented error_reason/error_source and no AI mocks; fixtures with AI-era classes or confidence-driven MANUAL_REVIEW moved
  to taxonomy classes / merchant enabled actions; policy versions v1.0 → v2.0. Each marked "M3 behaviour change".
- New: AiOffHotPathTest (2), ComplianceEngineTest 5b (5), RecoveryStrategyEngineTest strategy table (13).
- TestHygieneTest allowlist emptied (the two M3-owned wipers are rewritten).
DECISIONS:
- MANUAL_REVIEW→approval flows (OD-1) are now reached via merchant enabled actions; a legacy-class campaign reaches review
  (strategy MANUAL_REVIEW) but cannot be approved (compliance UNKNOWN_CLASSIFICATION) — fail closed, rejectable by a human.
- The demo's invented classes (NETWORK_TIMEOUT, AUTHENTICATION_FAILED, CARD_EXPIRED, UNKNOWN) mapped to taxonomy classes;
  its fake confidences/agent records go in U2.3.
Revert proofs:
- old orchestrator → `AiOffHotPathTest.paymentPathHasNoAiDependency:105 RecoveryOrchestratorService constructor takes com.mend.agent.AgentDecisionEngine`;
- 0.50 confidence gate back → `AiOffHotPathTest.paymentFlowWithAiServiceUnreachable:91 expected: <ACTION_PENDING> but was: <EXHAUSTED>` (+3);
- UNKNOWN-only compliance block → `ComplianceEngineTest.testUndocumentedClassBlocked:127 expected: <true> but was: <false>` ×5.
Review: running (fresh subagent). Next: U2.3.

#### PAUSED (usage limit) — resume point
- Branch `m3/honest-intelligence` is green at `31a1949` + progress commits (backend 887/0/0/0; frontend unchanged since Phase 1).
- U2.2 review subagent was still running when paused; its findings are NOT yet triaged — re-run the U2.2 review
  (diff `faf859a..31a1949`) before continuing.
- U2.3 started, uncommitted, in `git stash` "u23-frontend-wip": new `FailureClassificationPanel` (+3 tests) replacing
  `AgentDecisionStack` (deleted, 1 test). Remaining U2.3 work: frontend type/page cleanup (campaign detail AI tab and
  confidence, payment agent decisions, StartRecoveryModal agent decisions, actions agent fields, dashboard "% Conf",
  campaigns "AI Confidence" column, analytics AI confidence, landing "six agents" copy); settings bug: the escalation slider
  sends 0.1–1.0 to an integer 1–10 field (no rule uses it) → integer input with an honest label + test; backend deletion of
  agent/*, Agent* controllers, dto/ai, AiClassificationClient/FastApi client, agent fields on Review/Payment/StartRecovery
  DTOs, analytics aiConfidenceMetrics, demo/dev-seed fake values; ClassificationResultDto.taxonomyVersion; rewrite
  AiAgentIntegrationTest, AiClassificationClientTest, CrossTenantReadIsolation agent cases without lowering the count.
- Then U2.4–U2.7 as planned in `docs/m3/M3-plan.md`. Phase 2 is not merged; no `m3-complete` tag.
