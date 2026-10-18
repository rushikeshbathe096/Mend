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

#### U2.2 review (fresh subagent) — resumed after the pause
No AI call left on the payment path. Dispositions:
- FIXED MAJOR 1: pre-M3 rows named like taxonomy classes were authorized → a row decides only with a taxonomy version
  (`deterministicAndRecoverable`, strategy engine likewise); revert proof: old ComplianceEngine →
  `testLegacyRowWithTaxonomyNameBlocked:251 INSUFFICIENT_FUNDS @ 0.55 ==> expected: <true> but was: <false>`.
- FIXED MAJOR 2: AiOffHotPathTest now uses a spy on the real client at a non-answering address, runs to RECOVERED via the
  scheduler, `verifyNoInteractions`; type check widened to every path service.
- DEFERRED to U2.3 (frontend + analytics removal): MAJOR 3 null confidence shown as 0%; MINOR 4 averageConfidence 0.0.
- FIXED MINOR 5: class rule before strategy rules (legacy → UNKNOWN_CLASSIFICATION, no unapprovable review); revert proof:
  `testLegacyClassBlockedBeforeStrategyRules:266 expected: <UNKNOWN_CLASSIFICATION> but was: <STRATEGY_NOT_SUPPORTED>`.
- DOCUMENTED MINOR 6 (pre-M3 intents not re-classified at dispatch) in ADR-003 consequences.
- FIXED NIT 7 (exhaustive switch expression; imports).
- Also fixed: the demo now produces deterministic classification rows from simulated Razorpay error fields.
Verify: backend `[INFO] Tests run: 892, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
Next: U2.3 (restore stash "u23-frontend-wip").

### U2.3 — delete AI theatre (branch m3/honest-intelligence)
Plan: backend deletions → DTO/API honesty → demo + dev seed → tests rewritten (count kept) → frontend → verify → fresh review.
Commits: `1c2363f` backend, `f005c62` frontend.
- Deleted: agent/*, AgentOrchestrationController, AgentDecisionQueryController, AiClassificationClient +
  FastApiClassificationClient, dto/ai/*, AgentDecisionRecord entity/repository/DTO, AiClassificationException, legacy
  enums FailureClass / RecommendedAction; frontend AgentDecisionStack (+ test) and the "AI Agent Decisions" tab.
- Replacement for low-confidence MANUAL_REVIEW gating (ADR-003 §4): MANUAL_REVIEW comes only from the strategy table
  (a computed strategy the merchant has not enabled) and explicit review codes; a failure without evidence (UNCLASSIFIED,
  legacy row, no row) is blocked by compliance as UNKNOWN_CLASSIFICATION before the strategy rules.
- DECISION U2.3-a: API DTOs (campaign, classification, recovery decision, review item) drop confidence; the entity
  columns stay as history (no migration). `ClassificationResultDto.taxonomyVersion` added.
- DECISION U2.3-b: `agent_decision_records` stays as a legacy table: no writer, no API, rows untouched, no migration.
- DECISION U2.3-c: start-recovery reads approval from a PENDING review and compliance from the latest stored decision;
  no decision → `complianceStatus: null` (was a default "COMPLIANCE_ALLOWED"). SUCCESS (only a mock-mode synthetic
  capture) is labelled SYNTHETIC.
- DECISION U2.3-d: operational health returns only measured values (Redis stream sizes, status UP/UNKNOWN, null when not
  measured) instead of hardcoded HEALTHY / 45 ms / AI "UP" / fallback rate 0.0.
- DECISION U2.3-e: demo AGENT_FAILURE → UNCLASSIFIED_FAILURE (same safe outcome: EXHAUSTED, no action); dev seed classes
  come from FailureTaxonomy over simulated Razorpay reasons, no confidence/model; the UNCLASSIFIED segment is seeded with 0
  attempts and an UNKNOWN_CLASSIFICATION block (was 3 retries of a legacy UNKNOWN class).
- DECISION U2.3-f: settings escalation threshold is an integer 1–10 (backend validation); no rule reads it — labelled so.
- DECISION U2.3-g: `mend.ai.*` properties stay until U2.4 decides the reply-parsing client (removed in U2.6 if unused).
Tests (count kept): AiAgentIntegrationTest → AiTheatreRemovedIntegrationTest (3), AiClassificationClientTest →
HonestApiDtoTest (3), CrossTenantReadIsolation agent cases → payment detail / campaign read (2), AiOffHotPathTest proof →
no AI bean in the context; +2 CampaignControllerTest; frontend −1 AgentDecisionStack, +4 FailureClassificationPanel,
+1 settings slider.
Revert proofs:
- `.orElse("COMPLIANCE_ALLOWED")` → `CampaignControllerTest.startRecovery_noDecisionReportsNoComplianceStatusAndNoApproval:210`
- CampaignDto confidence getter → `HonestApiDtoTest.legacyConfidenceAndAgentFieldsAreNotExposed:53 expected: <false> but was: <true>`
- HEAD settings page → `SettingsPage › edits the escalation threshold as an integer from 1 to 10, not as an AI confidence`
- HEAD dashboard → `DashboardPage › 2. fetches dashboard APIs and renders real backend values on success`
Verify: backend `[INFO] Tests run: 894, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; frontend
`Tests: 57 passed, 57 total`, build `✓ Compiled successfully`, lint `✖ 1 problem (0 errors, 1 warning)` (pre-existing).

#### U2.3 review (fresh subagent): no blocker. Dispositions
- FIXED MAJOR 1: dev seed stamped real policy versions on segment-chosen decisions → "dev-seed".
- FIXED MINOR 2: seed/demo rows without evidence → shared `FailureClassification.evidence`; revert proof
  `Phase19MerchantProductIntegrationTest.lowRiskRetryScenarioRecoversThroughRealExecutionPath:143 expected: <insufficient_funds> but was: <>`.
- FIXED MINOR 3 (demo preset labelled), MINOR 4 (landing review route = MANUAL_REVIEW via merchant actions).
- FIXED MINOR 5: analytics counted legacy names as classified → `countFailureClasses`; revert proof
  `countFailureClasses › counts taxonomy classes, UNCLASSIFIED and legacy pre-M3 names separately`.
- FIXED MINOR 6: mock SUCCESS headed "Payment Recovered" → "Simulated Capture"; revert proof
  `StartRecoveryModal › allows user to select payment, review context, and trigger end-to-end start-recovery`.
- FIXED NIT 7 (health UP only with both sizes), NIT 8b (exact 404 asserted).
- KEPT NIT 8a: `mend.ai.*` stays; U2.4's reply client uses `mend.ai.base-url`, so AiOffHotPathTest's unreachable address
  still means "a call would hang" (DECISION U2.3-g).
Verify: backend `[INFO] Tests run: 895, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; frontend
`Tests: 59 passed, 59 total`, build OK, lint `✖ 1 problem (0 errors, 1 warning)`.

### U2.4 — customer-reply parsing (plan)
- V20 `customer_replies` (merchant_id, campaign_id, text ≤ 2000, status PENDING/PARSED/FAILED, attempts, next_attempt_at,
  last_error, created_at) and `reply_suggestions` (reply_id unique, intent, promised_date, abstain, valid,
  validation_error, model_id, model_version, prompt_version, effect, created_at); V21 `campaigns.contact_opted_out_at`.
  New tables / nullable column only: no existing row rewritten.
- API `POST/GET /api/v1/campaigns/{id}/replies` (TenantGuard user access + campaign must belong to the merchant),
  `POST /api/v1/campaigns/{id}/replies/{replyId}/retry` (FAILED only), `DELETE /api/v1/campaigns/{id}/opt-out`
  (MERCHANT_ADMIN, audited).
- `ReplyParsingJob` (scheduled, `FOR UPDATE SKIP LOCKED`, exponential backoff, attempt cap → FAILED) calls ai-service
  `POST /api/v1/parse_reply` through `ReplyParserClient`; `ReplySuggestionValidator` (strict: enum, ISO date, abstain
  boolean, no extra fields) → `ReplyPolicy`.
- DECISION U2.4-a: intent enum = OPT_OUT, ALREADY_PAID, DISPUTE, PROMISE_TO_PAY (optional promised_date), OTHER.
- DECISION U2.4-b: ai-service calls Gemini `generateContent` over httpx (already a dependency, no new one); the model id is
  the response's `modelVersion`; no `GEMINI_API_KEY` → `503 {"error":"model_unavailable"}` and no output.
- Policy: OPT_OUT → `contact_opted_out_at` + audit; compliance `CUSTOMER_OPTED_OUT` for new intents and in the executor's
  pre-dispatch gate (same transaction as the fence). ALREADY_PAID → AWAITING_PAYMENT link intents get `reconcile_after =
  now` (fenced by status), never NEEDS_RECONCILIATION; none → review `CUSTOMER_SAYS_PAID`. DISPUTE → review
  `CUSTOMER_DISPUTE` (appends to an open review). Others → shown only. Never RECOVERED.
- Retention: 90 days, purge job M5-owned (documented).

### U2.4 — done (review pending)
Commits: `8fe7e0c` ai-service, `3f6209d` backend, frontend panel (next commit after `3f6209d`).
- As planned, except DECISION U2.4-c: a single `model_id` column (the provider's `modelVersion`); no separate
  `model_version`, which would duplicate it.
- DECISION U2.4-d: the opt-out column is read-only in JPA and written only by conditional updates (a stale entity save
  cannot clear it). The executor gate reads it `FOR SHARE` in the dispatch transaction.
- DECISION U2.4-e: compliance policy version v2.0 → v2.1 (new rule; one assertion updated with an M3 behaviour-change
  comment).
- DECISION U2.4-f: ALREADY_PAID changes only `reconcile_after`. Status-fenced and no version bump, so an in-flight
  reconcile keeps its own fence.
- DECISION U2.4-g: PROMISE_TO_PAY's date is shown only (no reschedule).
Tests: ReplyParsingIntegrationTest (13, real PostgreSQL and threads), ReplySuggestionValidatorTest (15),
HttpReplyParserClientTest (4), TenantGuard table +4 methods, ai-service test_parse_reply (10), frontend panel (3).
Test-time fact: the DB session time zone is Asia/Kolkata while Hibernate stores Instants as UTC wall-clock, so tests make
rows due relative to the row's own timestamps, never `now()`.
Revert proofs (all five run at once, each targeting one test):
- `optOutBlocksContactAtComplianceAndDispatch:151 Expected ComplianceBlockedException` (no dispatch gate)
- `staleCampaignSaveKeepsOptOut:173 expected: not <null>` (writable column)
- `invalidAndAbstainedHaveNoEffect:257 expected: <NONE> but was: <CONTACT_OPTED_OUT>` (no abstain check)
- `staleResultIsDropped:320 expected: <0> but was: <1>` (unfenced markParsed)
- `alreadyPaidNeverTouchesNeedsReconciliation:203 expected: <REVIEW_CUSTOMER_SAYS_PAID> but was: <PAYMENT_LOOKUP_BROUGHT_FORWARD>` (unfenced lookup)
Verify: backend `[INFO] Tests run: 938, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; frontend
`Tests: 62 passed, 62 total`, build OK, lint 0 errors; ai-service `61 passed`.


### U2.5 — evaluation (commit `2bc479d`)
- `docs/m3/eval/replies-v1-{dev,test}.jsonl` (60 + 40 rows, all SYNTHETIC), LABEL-GUIDE.md, RESULTS.md,
  results-replies-v1.json; `ai-service/eval/run_eval.py`.
- DECISION U2.5-a: the keyword rules were written against the dev split (macro F1 1.0 there = upper bound). The test
  split was written after the rules were frozen and is the headline: baseline macro F1 **0.1125**.
- Model: **TBD**, no GEMINI_API_KEY in the environment (checked for presence only); the report carries no model numbers.
Tests: ai-service `73 passed` (+12 test_eval.py).

### U2.6 — ai-service reduction (plan)
- Keep `/health` (honest: model configured yes/no, no provider guess) and `/api/v1/parse_reply`; delete agent/*,
  `/api/v1/classify`, the heuristic, the MCP tools, the checkpointer, the benchmark JSONs and scripts.
- requirements: fastapi, uvicorn, pydantic, httpx, pytest (exactly what is imported).
- Tests: the count (73) must not drop (HARD STOP). Deleted tests of deleted code are replaced by tests of the remaining
  surface: auth, PII/secret handling, provider error mapping, prompt-injection pass-through, health.

#### U2.4 review (fresh subagent): no blocker. Dispositions
- FIXED MAJOR 1 (poison reply retried forever / over-long untrusted strings): revert proof
  `ReplyParsingIntegrationTest.untrustedValuesCannotPoisonTheJob:348 expected: <FAILED> but was: <PENDING>`.
- FIXED MAJOR 2 (compliance-blocked abandon not audited): revert proof
  `optOutBlocksContactAtComplianceAndDispatch:156 the abandoned claim is audited ==> expected: <1> but was: <0>`.
- FIXED MINOR 3 (bring-forward vs reconcile lease; rate limit): revert proof
  `alreadyPaidBringsForwardTheLookupOnly:199 expected: <…03:03:34…> but was: <…03:02:34…>`.
- FIXED MINOR 4 (lease vs batch: one row per claim), MINOR 5 (promised_date presence) with revert proof
  `ReplySuggestionValidatorTest.invalidSuggestions:49 expected: <Invalid[error=PROMISED_DATE_MISSING]>`.
- FIXED MINOR 6 (reconciler now run in the ALREADY_PAID tests), MINOR 7 (behavioural payment-path proof).
- DOCUMENTED MINOR 8 in ADR-003 (approving a reply review resumes collection; separate review kind M4-owned).
- FIXED NIT 9 (bounded read), NIT 10 (banner text, refetch), NIT 11 (system admin may clear).
Verify: backend `[INFO] Tests run: 940, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; frontend
`Tests: 62 passed, 62 total`, build OK, lint 0 errors.

#### U2.5 review (fresh subagent): no blocker. Dispositions (commit `e2c72c1`)
- FIXED MAJOR 1+2: eval validity parity with the backend (all keys, real date, ASCII). Revert proof: old `valid()` →
  `test_validity_matches_the_backend_schema[suggestion6/7/8-False]` fail.
- FIXED MAJOR 3 (documented): the test split was written knowing the frozen rules → RESULTS.md calls 0.1125 close to an
  adversarial lower bound; model comparisons need `replies-v2`.
- FIXED MAJOR 4 (recorded-results test compares baseline + hashes only), MINOR 5 (provider_errors apart), MINOR 6
  (dated/undated date match), MINOR 7 (hand-checked metrics + stubbed model path), NIT 10 (conventions documented;
  empty/mixed files refused).
- DOCUMENTED MINOR 8 (label limitations in LABEL-GUIDE; v1 is never edited).
- DEVIATION (MINOR 9): the spec names one `replies-v1.jsonl`; shipped as dev + test splits of `replies-v1` (U2.5-a).

### U2.6 — ai-service reduction (commit `53ec57d`)
- Deleted agent/* (LangGraph graph, router, state, tools, MCP tools, checkpointer, llm), `/api/v1/classify` + heuristic +
  hardcoded confidences, the benchmark JSONs. main.py = `/health` + `/api/v1/parse_reply`.
- requirements: fastapi, uvicorn, pydantic, httpx, pytest. `.env.example` cleaned; backend `mend.ai.read-timeout-ms`
  removed (unused).
- Tests: count kept (73 → 79 at U2.6, 84 after the U2.5 fixes); tests of deleted code replaced by tests of the remaining
  surface (auth, deleted routes gone, PII/key never logged, key only in a header, prompt injection is data, malformed
  provider answers).
Verify: `scripts/ci/ai-service.sh` (fresh venv from requirements) → `79 passed` at U2.6; local `84 passed` after U2.5 fixes.

#### U2.6 review (fresh subagent): no blocker. Dispositions
- FIXED MAJOR 1 (non-object provider JSON → 500): revert proof: old reply_parser →
  `test_provider_body_that_is_json_but_not_an_object_is_a_model_error[...]` 4 failures.
- FIXED MAJOR 2 (README still described LangGraph/MCP/six agents), MINOR 3 (health-check doc), MINOR 4
  (`hmac.compare_digest` + startup warning; auth stays optional for localhost as documented), MINOR 5 (blank text),
  NIT 6 (.gitignore), NIT 7 (.dockerignore).
- Out-of-scope note answered: backend full run after U2.6 = `[INFO] Tests run: 940, Failures: 0, Errors: 0, Skipped: 0`.
Verify: ai-service `90 passed`.

### U2.7 — close-out
- Final runs (CI scripts): backend `[INFO] Tests run: 940, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS; frontend
  `Tests: 62 passed, 62 total`, lint `✖ 1 problem (0 errors, 1 warning)`, build OK; ai-service (fresh venv) `90 passed`;
  migrations: 21 applied, validate OK. Recorded in `docs/m3/m3-final-test-run-summary.txt`.
- `docs/m3/M3-summary.md` (12 lines), ADR-003 **Accepted** with an evidence table, §50 checklist 13/13 ticked with commits,
  AGENTS.md milestone → "M3 complete — next: M4 (needs design pack)".
- DECISION U2.7-a: the M3 invariants 16–18 from the design doc are added to AGENTS.md (as M2 did for 11–15).
