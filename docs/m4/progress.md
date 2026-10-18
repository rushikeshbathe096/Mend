# M4 progress (orchestrator state file)

Re-read before every unit; append after every unit. Branch `m4/evaluation` from local `main` @ `9fec57f`
(tag `m3-followups`). Merge `--no-ff` into local `main` and tag `m4-complete` at the end. Never pushed.

Verify: backend `cd backend && env -u JWT_SECRET ./mvnw clean test`; frontend `cd frontend && npm test -- --ci && npm run
build && npm run lint`; ai-service `cd ai-service && pytest`; migrations `scripts/ci/migrations.sh`.

## Precondition (U4.0)
- Tag `m3-followups` on `main` (`9fec57f`). `env -u JWT_SECRET ./mvnw clean test` →
  `[INFO] Tests run: 971, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- origin/main = `9fec57f` (pushed by the owner). Latest GitHub Actions run on main (`gh run list`): CI, `success`,
  2026-10-18T07:40:19Z, run 39108539990, 1m52s.

## Standing decisions
- DECISION (setup): the brief (§27, §28, §51, §54, §60, §61) is not in the repository. §54 headings follow the M2/M3 design
  docs; the §51 checklist is derived from the M4 rules of the task prompt (`docs/m4/M4-plan.md`); §60 is
  `docs/m4/metric-tree.md`. §27/§28/§61 (orchestration rules) are applied as given in the task prompt.

## Log

### U4.0 design pack
Plan: design doc (§54 headings), ADR-004 Proposed, plan with refined units + §51 checklist, metric tree, AGENTS.md
invariants 19–25.
Written: `docs/m4/M4-design-doc.md`, `docs/adr/ADR-004-evaluation.md`, `docs/m4/M4-plan.md`, `docs/m4/metric-tree.md`,
AGENTS.md (invariants 19–25, milestone line "M4 in progress").
DECISIONS:
- U4.0-a: randomization unit = `invoice_key` within a merchant; `HMAC-SHA256(salt, invoice_key)` → unsigned u64 mod 10 000
  → CONTROL iff bucket < `holdout_rate_bp`. Integer basis points everywhere (no float in stored data).
- U4.0-b: default holdout 0 (off, merchant opt-in), cap 0.5, pilot recommendation 0.2.
- U4.0-c: assignment only on the CLASSIFIED → ELIGIBLE edge (first eligibility), inside `transitionState`'s transaction;
  re-entries to ELIGIBLE keep the stored assignment; pre-M4 campaigns are UNASSIGNED (not in the experiment).
- U4.0-d: CONTROL = no strategy, no intent, no provider call; refused in `ActionIntentService` and by a DB trigger. Replies,
  opt-out, disputes, late capture and safety reviews unchanged. Approving a review on a CONTROL campaign cannot create an
  intent (it would be recovery contact).
- U4.0-e: ITT analysis; outcome = RECOVERED with provider evidence by `as_of`; SYNTHETIC attribution never in MEASURED figures.
- U4.0-f: new evaluation tables have no FKs (ids only) so the append-only log outlives row deletion; immutability by trigger.
- U4.0-g: power table computed (unpooled normal approximation) and reproduced by a unit test in U4.4.
Next: U4.1 (review of U4.0 runs in parallel).

#### U4.0 review (fresh subagent; AGENTS.md + U4.0 spec + diff). Power table and Newcombe description verified correct.
- FIXED BLOCKER 1 (outcome measured differently per arm: terminal TREATMENT campaigns lose late captures): outcome =
  provider capture evidence (RECOVERED transition or the capture-on-terminal audit codes) within a fixed horizon H
  (default 14 d) after first eligibility, read the same in both arms; only mature invoices analysed.
- FIXED MAJOR 2 (no horizon / peeking): H fixed; INTERIM label before the pre-registered n (no alpha spending in M4).
- FIXED MAJOR 3 (median among recovered not causal): cumulative recovery by day 1/3/7/14 (ITT) is the time figure; the
  median is labelled descriptive.
- FIXED MAJOR 4 (snapshots not point-in-time): rebuilt as of `as_of` from append-only timestamped sources only.
- FIXED MAJOR 5 (randomization vs analysis unit): a later campaign of an assigned invoice copies the stored arm
  (`INVOICE_REUSE`); the analysis unit is the invoice.
- FIXED MAJOR 6 (invariant 23 vs U4.8c): invariant 23 scoped to evaluation figures from VALID snapshots; live analytics
  labelled non-causal; UI T/C figures come from the latest VALID snapshot.
- FIXED MINOR 7 (SUTVA stated + measured), 8 (SRM chi-square p < 0.001), 9 (per-stratum, no pooling), 10 (cost per
  incremental rupee as decision metric), 11 (money exactness, n ≥ 100 / unstable < 300, coarse median), 12 (failure modes:
  log-insert rollback, post-M4 = V22 `installed_on`, cancel keeps evidence, refunds TBD), 13 (HMAC merchant ref, column
  allow-list, day + offsets), 14 (erasure procedure M5-owned, documented).
- FIXED NIT 15 (10 000 bp everywhere), 16 (`Long.remainderUnsigned` stated; tolerance fixed in the test at 5σ), 17 (status
  wording), 18 (AGENTS.md scope lines), 19 (transaction wording).

### U4.1 holdout assignment
Plan: V22 (rate column, salts, immutable assignments, CONTROL intent trigger), pure `HoldoutAssigner`,
`ExperimentAssignmentService` hooked into `transitionState` on CLASSIFIED → ELIGIBLE, CONTROL skipped in lifecycle /
orchestrator / intent creation / approval, config API `holdoutRateBp`.
Commits: `4d851fe` feature, then the test-hygiene follow-up commit.
- DECISION U4.1-a: refusal paths (intent creation, review approval for CONTROL) write no audit row: nothing changes state, and
  an audit row in the throwing transaction would roll back anyway (see out-of-scope finding OOS-1).
- DECISION U4.1-b: a CONTROL review can be left PENDING / rejected; approval is refused (it resumes collection).
- DECISION U4.1-c: no executor check for CONTROL: an assignment is written only on CLASSIFIED → ELIGIBLE, before any intent
  can exist for the campaign, and the V22 trigger makes a CONTROL intent impossible.
- DECISION U4.1-d: TRUNCATE immutability is checked in `pg_trigger`, not executed (TestHygieneTest forbids table-wide
  statements on the shared test database).
Tests: HoldoutAssignerTest 7 (known answers from Python hmac, 100 000 seeded keys within 5σ, decile chi-square < 27.877),
ExperimentPurityTest 1, HoldoutAssignmentIntegrationTest 11. CampaignLifecycleServiceTest audit count 4 → 5 (M4 behaviour
change comment).
Revert proofs:
- CONTROL skip removed from lifecycle + orchestrator → 6 errors (`controlTakesNoAction:143->failPayment:114 » UnexpectedRollback`:
  the intent guard then throws inside the classification transaction).
- invoice reuse off → `secondCampaignOfInvoiceReusesArm:273 expected: <CONTROL> but was: <TREATMENT>`.
- V22 intent trigger removed → `controlRefusedOnEveryPath:178 Expected DataAccessException to be thrown, but nothing was thrown`.
Verify: backend `[INFO] Tests run: 990, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
Out-of-scope finding OOS-1: `HumanApprovalService.approveReview` writes `REVIEW_APPROVAL_BLOCKED` and then throws in the
same transaction, so that audit row is always rolled back (pre-existing).

#### U4.1 review (fresh subagent; ran the tests on a clean copy of `941d456`): no blocker. Dispositions (commit `e7eac65`)
- FIXED MINOR 1 (two campaigns of one invoice could both hash): per-invoice `pg_advisory_xact_lock` before the lookup; test
  `concurrentCampaignsOfOneInvoice` (real threads, latch). Revert proof (lock removed), 3 of 3 runs:
  `concurrentCampaignsOfOneInvoice:388 » IllegalArgument duplicate element: HASH`.
- FIXED MINOR 2 (ADR said "audited"): ADR-004 §3 now says a refusal writes no audit row (it would roll back) and maps to 409.
- FIXED MINOR 3: `concurrentSaltCreation` (two threads, merchant without salt → one salt row).
- FIXED MINOR 4: CONTROL keeps OPT_OUT and DISPUTE reply effects (`controlKeepsCustomerRequests`); manual start-recovery on
  CONTROL makes no intent / provider call and says "Holdout CONTROL" (`controlManualStart`).
- FIXED MINOR 5 as DECISION U4.1-e: invoice reuse only while the holdout is on; at rate 0 a former CONTROL invoice's new
  campaign is TREATMENT (merchant ended the experiment). Revert proof: `holdoutOffEndsTheExperimentForNewCampaigns:429
  expected: <TREATMENT> but was: <CONTROL>`. My own `secondCampaignOfInvoiceReusesArm` now changes the rate 0.5 → 0.1.
- FIXED MINOR 6: rates 0.01 / 0.2 / 0.5 at 4σ and a 100-bin chi-square (< 148.230, df 99, p 0.001).
- FIXED NIT 7 (comment), 8 (68 %), 9 (Javadoc), 10 (HoldoutControlException → 409; start-recovery message).
- OOS (noted for U4.8c): CONTROL campaigns stay ELIGIBLE, so live analytics count them as "active"; label them.
- OOS (pre-existing): the orchestrator's catch of ComplianceBlockedException from the proxied intent creation would leave the
  outer transaction rollback-only.

### U4.2 decision log
Plan: V23 `decision_log` (append-only trigger, chosen ∈ alternatives CHECK, unique ASSIGNMENT per campaign and
STRATEGY/COMPLIANCE per source row), `DecisionLogService` (MANDATORY propagation) called from the assignment, strategy
persist, compliance persist and executor fence (DISPATCHED), pre-dispatch block (BLOCKED + code) and expiry (EXPIRED).
Commit: `5c3032a`.
- DECISION U4.2-a: propensities in basis points; deterministic choices 10 000. Strategy alternatives = all strategies when the
  merchant config does not restrict them, else the enabled ones + MANUAL_REVIEW + NO_ACTION (engine rule 5's matching).
- DECISION U4.2-b: DISPATCH rows only where the executor applies a transition (dispatch fence, blocked abandon, expiry
  abandon). SAFE_RETRY resends by the reconciler are not new policy decisions (not logged; noted for M5).
- DECISION U4.2-c: the opt-out found at dispatch throws a typed `OptedOutAtDispatchException` (same message) so its code
  is logged without parsing text.
Tests: DecisionLogIntegrationTest 10 (one row per point on the TREATMENT, CONTROL, compliance-blocked, blocked-at-dispatch,
expired and idempotent paths; failed insert rolls back the dispatch; immutability; chosen ∉ alternatives refused).
Revert proofs:
- strategy log call removed → 4 failures (`treatmentPathWritesOneRowPerPoint:142->single exactly one row per decision point: []`).
- dispatch log moved after the provider call (outside the fence) → `failedLogInsertRollsBackTheDispatch:262 expected: <null>
  but was: <2026-10-18T18:14:21.793983Z>` (dispatched despite the failed log).
- (A swallowed log failure inside the fence is still rolled back by Spring's rollback-only marking: safe either way.)
Verify after both: backend `[INFO] Tests run: 1006, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`;
`scripts/ci/migrations.sh` → FreshDatabaseMigrationTest 1/0/0/0, BUILD SUCCESS.

#### U4.2 review (fresh subagent): no blocker. Dispositions (commit `c4b8b5b`)
- FIXED MAJOR 1 (rows lacked versions): every row carries the versions in force (taxonomy of the latest classification,
  engine strategy/compliance versions, stored hash version; DISPATCH carries the authorizing decision's version; an OD-1
  override COMPLIANCE row has no strategy version). ADR-004 §4 amended. Revert proof (versions nulled) →
  `treatmentPathWritesOneRowPerPoint:144 expected: <razorpay-error-taxonomy-v1> but was: <null>`.
- FIXED MINOR 2 (demo recorded a dispatch without a row): demo dispatch + DISPATCH row (`DEMO_SIMULATED`) in one transaction.
- FIXED MINOR 3 (sweeper expiry unlogged): `INTENT_EXPIRED_UNCLAIMED` row. Revert proof →
  `sweeperExpiryIsLogged:287->single exactly one row per decision point: []`.
- REJECTED MINOR 4 (unique DISPATCH per intent): a deferred intent is legitimately dispatched again (M2
  `deferFromExecuting`), so several DISPATCH rows per intent are correct; documented in ADR-004 §4.
- FIXED MINOR 5: tests for LEGACY_DECISION dispatch row (in LegacyDecisionDispatchTest), OD-1 override second COMPLIANCE
  row, sweeper expiry, STRATEGY log failure rolls the whole decision back, `assertEquals(CLAIMED)` after a failed fence log.
- FIXED NIT 6 (`Persistable`, no merge SELECT), NIT 7 (override → null strategy version), NIT 8 (comment on the BLOCKED path).
- Test constructions changed for the new claim-service dependency (ActionIntentClaimServiceTest, OneInFlightPerInvoiceTest).

### U4.3 evaluation snapshots
Plan as in M4-plan (point-in-time, horizon outcome, PII-free, validated, idempotent). Commit `9a6ff62`.
- V24 `eval_snapshots` (immutable; status VALID ⇔ no errors, CHECK). Files under `mend.eval.snapshot.dir` (default
  `${java.io.tmpdir}/mend-eval-snapshots`, outside the repository): campaigns.jsonl, decisions.jsonl, campaigns.csv,
  manifest.json; sha256 over campaigns.jsonl + decisions.jsonl.
- Pure `com.mend.evaluation.snapshot` (CampaignFacts → SnapshotRow via SnapshotBuilder; SnapshotCodec; SnapshotValidator);
  `EvalSnapshotService` reads append-only timestamped sources only; `EvalSnapshotController`
  (`POST/GET /api/v1/internal/eval/snapshots`, SYSTEM_ADMIN); `EvalSnapshotJob` (nightly, `mend.eval.snapshot.schedule-enabled`,
  default off); `scripts/eval/snapshot.sh` (curl; `bash -n` OK; shellcheck not installed here).
- DECISION U4.3-a: eligibility time = first `CAMPAIGN_STATE_TRANSITION` → ELIGIBLE audit row; post-M4 = at/after V22
  `installed_on`. Capture evidence = RECOVERED transition (attribution → MEND/ORIGINAL/SYNTHETIC/UNKNOWN) or the codes
  `CAPTURED_ON_TERMINAL_CAMPAIGN`, `CAPTURED_EVIDENCE_NOT_APPLIED` (MEND), `ORIGINAL_PAYMENT_CAPTURED_ON_TERMINAL_CAMPAIGN`.
- DECISION U4.3-b: contacts = DISPATCHED decision rows (append-only) plus `dispatched_at` of intents with no DISPATCH row
  (pre-U4.2); `dispatched_at` alone is overwritten by a re-dispatch, so it is not point-in-time.
- DECISION U4.3-c: provider mode from configuration: mock → whole snapshot SYNTHETIC; razorpay key prefix → test / live
  (test mode is MEASURED provider evidence but never revenue; the report says so).
- DECISION U4.3-d: strategy / compliance per row come from the decision log only (pre-U4.2 campaigns have none; they are
  UNASSIGNED and outside causal figures anyway). customer_ref is null for webhook campaigns (no customer key): interference
  is TBD until a customer key exists.
- DECISION U4.3-e: a re-run with identical parameters and checksum returns the stored snapshot (no duplicate row).
Tests: SnapshotBuilderTest 6, SnapshotValidatorTest 13 (11 parameterized defects + 2), EvalSnapshotIntegrationTest 4,
EvaluationPurityTest 1.
Revert proof: audit `as_of` filter removed → `idempotentAndPointInTime:203 expected: <3d3e…> but was: <7e16…>`.
Verify: backend `[INFO] Tests run: 1034, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

#### U4.3 review (fresh subagent; ran the 24 tests on a clean copy): 4 MAJOR. Dispositions (commit `dfa7a3d`)
- FIXED MAJOR 1 (label from current config): DECISION U4.3-f — MEASURED only in `razorpay-live` with a non-simulated
  source; mock, simulator, Razorpay test mode, unknown key mode, demo webhooks (`MEND_DEMO_SIMULATED`) and SYNTHETIC
  captures are SYNTHETIC. Rule written into the manifest. Revert proof (old rule) →
  `SnapshotBuilderTest.syntheticLabels:126 razorpay-test ==> expected: <true> but was: <false>`.
- FIXED MAJOR 2 (late commits / mixed reads): `as_of ≤ now − mend.eval.snapshot.settle-minutes` (default 15; tests 0) and
  one REPEATABLE READ transaction (`EvalSnapshotTimingTest.settleMargin`).
- FIXED MAJOR 3 (non-atomic files): temp dir → atomic move after commit (TransactionSynchronization), deleted on rollback;
  a reused snapshot with missing/mismatching files is rewritten (`tenantScopedAndSelfHealing`). Default dir stays
  `${java.io.tmpdir}` (documented; set `mend.eval.snapshot.dir` in any real deployment).
- FIXED MAJOR 4 (validator gaps): state RECOVERED with no attribution (also outside the horizon) → RECOVERED_WITHOUT_ATTRIBUTION;
  recovered without capture_source → SCHEMA_INCONSISTENT. Revert proof → `SnapshotValidatorTest.reviewDefects:65`.
- FIXED MINOR 5: post-M4 = earlier of configured `mend.eval.m4-start` and the merchant's first assignment ≤ as_of
  (`flyway_schema_history` dropped). MINOR 6: legacy-dispatch subquery bounded by as_of; V13-backfilled times documented
  (UNASSIGNED rows only). MINOR 7: audit_logs append-only by convention only — documented in ADR-004 §5. MINOR 8: unknown
  merchants in the filter are refused. MINOR 9: parameters truncated to microseconds; V25 unique index + `ON CONFLICT DO
  NOTHING`. MINOR 10: decisions file validated (orphans, propensity range, chosen ∈ alternatives, point, offset) plus rate,
  TREATMENT propensity, capture-within-horizon and currency checks. MINOR 11: checksum over per-file hashes (manifest
  records both). MINOR 12: failure webhooks batch-read per chunk. MINOR 14: TREATMENT contact via the real scheduler,
  merchant-filter isolation, nightly-window arithmetic (`EvalSnapshotTimingTest`).
- DOCUMENTED MINOR 13 (attribution is as of as_of, any horizon), NIT 15 (raw merchant ids only in the admin-side DB row),
  NIT 17 (amount = failed payment amount; captured amount TBD). FIXED NIT 16 (static mapper).

### U4.4 metrics + intervals
Plan as in M4-plan. Commit `e7baa36`.
- `com.mend.evaluation.stats`: Wilson, Newcombe (method 10), seeded percentile Bootstrap (≥ 2 000, stratified, exact
  BigDecimal means), Median, SampleSize (design-doc table reproduced), ChiSquare SRM (erfc).
- `com.mend.evaluation.metrics`: SnapshotFiles (sha + re-validation; tampered refused; INVALID refused without
  `--allow-invalid`), MetricsReport (invoice unit, per holdout-rate stratum, per merchant and overall; rates, cumulative
  by day, money, descriptive median, contact rate, attribution, by class, by strategy/channel, cost, system health),
  ReportWriter (Markdown + JSON), `cli/ReportCli` + `scripts/eval/report.sh` (jar + PropertiesLauncher; usage smoke OK).
- DECISION U4.4-a: an invoice's outcome is its first campaign's row (the HASH assignment, else the earliest); later campaigns
  of the same invoice are not separate units.
- DECISION U4.4-b: money figures use INR rows with a known amount only; unknown / other-currency counted and excluded.
- DECISION U4.4-c: snapshot label = SYNTHETIC if any row is SYNTHETIC (conservative).
Tests: StatisticsTest 12 (published Newcombe example 56/70 − 48/80 → 0.0524 … 0.3339; Wilson; bootstrap; power table),
MetricsReportTest 6 (hand-computed rates/money/cost, insufficient data, TBD, INTERIM/FINAL, integrity, CLI).
Verify: backend `[INFO] Tests run: 1059, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

#### U4.4 review (fresh subagent; formulas re-computed in Python, all correct): 2 MAJOR + test MAJOR. Dispositions (`3f37a59`)
- FIXED MAJOR 1 (outcome was the chosen campaign's, not the invoice's): snapshot schema **v2** adds invoice-level columns
  computed in the builder from exact times (first campaign, campaigns, mature, recovered within H of the invoice's first
  eligibility across all its campaigns, capture offset/source, contacts across all campaigns). Units = first rows; a later
  re-hashed campaign's contact now shows as CONTROL contamination (ALERT). Revert proof (campaign-level outcome) →
  `invoiceLevelOutcome:172 expected: <2.50 %> but was: <0.00 %>`.
- FIXED MAJOR 2 (decision metric without uncertainty): cost per incremental rupee carries the money interval
  (cost ÷ bounds), inherits UNSTABLE, is TBD when the money interval reaches 0, notes the configured assumption. Revert proof
  → `moneyAndCost:132 inherits the money figure's instability expected: <UNSTABLE> but was: <OK>`.
- FIXED MAJOR 18 (tests): exact percentile-index test, erfc at p 1e-3 / 1e-4, Newcombe edges, invoice collapse, strata +
  SRM ALERT, label fail-closed, cost per observed rupee, money status.
- FIXED MINOR 3 (label fails closed: MEASURED only non-empty + all MEASURED + razorpay-live), 4 (per-arm money bootstrap CI,
  n ≥ 100), 5 (median "insufficient data"), 6 (by-strategy min n), 7 (non-numbers labelled TBD), 8 (per-class EXPLORATORY),
  9 (INTERIM/FINAL per stratum), 10 (multiplyExact, cost ≥ 0), 11 (same subset for cost and money), 12 (manifest params in
  the checksum; safe snapshot id), 13 (renamed "amount of recovered invoices … failure amount, not captured amount"),
  NIT 14 (pp), 15 (Markdown escaping, separated notes), 16 (short refs), 17 (LATE_CAPTURE naming; north-star figure).
Verify: backend `[INFO] Tests run: 1071, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

### U4.5 replay simulator
Commit `58d455e` (adapted to snapshot v2 in `3f37a59`).
- `com.mend.evaluation.sim`: SyntheticGenerator (seeded; documented reason/amount weights), OutcomeModel (documented
  assumptions: late capture / link payment per class, exponential times, link TTL 72 h; retries not wired by default, as in
  production), ReplaySimulator (production FailureTaxonomy, `ClassificationResult.deterministicClass`, RecoveryStrategyEngine,
  ComplianceEngine, HoldoutAssigner; common random numbers per event), PolicyVariant (B current / A UNCLASSIFIED
  ineligible as an eligibility gate in front of the production engines), PolicyComparison (paired bootstrap of per-failure
  differences); `cli/SimulateCli` + `scripts/eval/simulate.sh`.
- DECISION U4.5-a: policy A no longer exists in production code; it is expressed as an input gate, never a copied engine.
- DECISION U4.5-b: retry timing is compared only with *hypothetical* wired retries (labelled so).
Tests: ReplaySimulatorTest 6 (reproducible; engines are the production classes and agree with direct calls; no DB-typed
fields; CONTROL never contacted; B gives UNCLASSIFIED links only; A ineligible; paired draws; VALID SYNTHETIC snapshot;
generator weights). Revert proof (unseeded draws) → `pairedDraws:107`, `reproducible:48` fail.

#### U4.5 review (fresh subagent): 5 MAJOR + test MAJOR. Dispositions (`c1f5c2e`)
- FIXED MAJOR 1 (retry contacts diverged): as in production (Razorpay mode), an allowed RETRY_* is dispatched (DISPATCH row,
  a contact for the snapshot) and captures nothing unless retries are wired. OOS-2 recorded: production counts a NotSupported
  retry dispatch as a contact (EvalSnapshotService + the dispatch fence), which overstates exposure for retry campaigns.
- FIXED MAJOR 2 (invented retry timing): `com.mend.strategy.ActionPolicy.scheduleDelay` (extracted from
  `ActionIntentService.calculateScheduledTime`, which now calls it) drives RETRY_LATER in the simulator; the timing comparison
  varies the merchant's contact window (1 h vs 48 h). Test `retryDelayFromProduction`.
- FIXED MAJOR 3 (action after recovery): no contact when the original payment was captured first. Revert proof →
  `noActionAfterRecovery:124 expected: <true> but was: <false>`.
- FIXED MAJOR 4 (snapshot replay): `ReplayEvent.storedTaxonomyVersion` passed through (legacy rows fail closed);
  `SnapshotReplay.events` adapter (day + rank, outcomes re-drawn, documented); test `snapshotReplay`.
- FIXED MAJOR 5 (invoice reuse): per-invoice state — arm copied (INVOICE_REUSE while the holdout is on), one link in flight
  (`duplicateActionExists` → DUPLICATE_ACTION from the production engine); generator `reuseShare`. Revert proof →
  `reusedInvoices:156`.
- FIXED MINOR 6/7 (`ActionPolicy.eligible`, `ActionPolicy.strategyAlternatives`, shared with CampaignLifecycleService /
  DecisionLogService), 8 (merchant config in Config; documented fixed assumptions; `restrictedMerchantConfig`), 9 (draws keyed by
  mix64(seed, campaign id), split per draw type; events replayed in eligibility order), 10 (StrictMath.log), 11 (per-campaign
  horizon cap), 12 (Monte Carlo caveat + assumptions table), 13 (tests: CLI end-to-end reproducibility and labels, reuse,
  replay, restriction, retry delay), NIT 14 (UTF-8), 15 (per-merchant domain-separated salts), 16 (EXECUTING documented).
Verify: backend `[INFO] Tests run: 1078, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

### U4.6 synthetic experiment run
Commits: `5dff21e` (end-to-end test), `2478d9b` (results), U4.6 review fix commit below.
- `docs/m4/results/`: `sim-20261018-10000.md|json` (report from snapshot files), `-policies.md` (true effect, B vs A,
  retry timing, assumptions), `-manifest.json`, README (setup, headline, error analysis, real data TBD).
- Headline (SYNTHETIC, n 10 000, holdout 0.2): incremental recovery rate 8.24 pp (6.61 … 9.77), true effect under the model
  7.94 pp (computed on the same draws), SRM p 0.783316, CONTROL contact 0.00 %. UNCLASSIFIED 2 522; LEGACY_DECISION 0
  (synthetic population has none; real count TBD).
- OOS-3 (M5): retry classes (INSUFFICIENT_FUNDS, BANK_TECHNICAL, GATEWAY_ERROR; 42.65 % of synthetic failures) get a
  retry, which cannot capture (debits not wired, NotSupported → review): wire debits or send those classes a link.
- OOS-4 (pre-existing): `MerchantConfigService.createDefaultConfig` writes `enabledRecoveryActions = "RETRY_PAYMENT,SEND_EMAIL"`;
  RecoveryStrategyEngine matches strategy names by substring, so such a merchant gets MANUAL_REVIEW for every campaign
  (approval issues a link, OD-1).

#### U4.6 review (fresh subagent; regenerated the run byte for byte, recomputed every README number): fixes
- FIXED MAJOR 1 (e2e test dispatched only 100 of 121 intents; weak assertions): polls until nothing is claimable;
  asserts created == dispatched, recovered > 0, MEND recoveries > 0 and equal in the snapshot, per-arm "k of n" == the
  database's per-arm RECOVERED counts.
- FIXED MINOR 2 (real amounts in late captures; README describes the stored-webhook entry and the audit-only time shift),
  3 (`cliIsReproducibleAndLabelled` compares the snapshot directory; new golden test `committedRunIsReproducible` against the
  committed manifest sha and report JSON), 4 (3 426 TREATMENT retries; 42.65 % retry classes), 5 (review item not modelled;
  contact rate overstated by OOS-2), 6 (exact merchant config + OOS-4), 7 (true effect computed: `PolicyComparison.trueEffect`
  = 7.94 pp, inside the estimate's CI), 8 (this entry; OOS-3/4), NIT 9 (manifest copy step), 10 (n on every headline figure).

### U4.7 retrieval-augmented reply parsing (gated)
Commits: `319f6ca` (ai-service few-shot prompt), `2b1a096` (store, flag, job, harness, docs).
- GEMINI_API_KEY absent (presence checked only) and `replies-v2` absent → metrics TBD, flag stays off
  (`docs/m4/eval/retrieval-results.json`: KEEP_DISABLED). Details: `docs/m4/eval/RETRIEVAL.md`.
- V26 `reply_label_examples` (source SYNTHETIC|CONSENTED CHECK, append-only, tsvector 'simple' + GIN);
  `ReplyExampleStore` (import, `similar` = ts_rank over OR of lexemes, k ≤ 5); `ReplyParsingJob` calls `parse(text, examples)`
  only with `mend.reply.retrieval.enabled=true` (default false), else exactly `parse(text)`; `HttpReplyParserClient` sends
  `examples`; ai-service `reply-parse-v2-fewshot` with JSON-encoded examples (≤ 5, validated); `eval/retrieval_eval.py` with
  `adoption_decision` (ADOPT only if macro F1 improves on the blind set without more invalid output).
- DECISION U4.7-a: the old `ReplyParserClient.parse(text)` contract is unchanged (default method for the examples variant), so
  every existing stub and the M3 behaviour stay as they were with the flag off.
- DECISION U4.7-b: the store is loaded only from the SYNTHETIC dev split of replies-v1 (never the held-out test split).
Tests: backend ReplyRetrievalIntegrationTest 3, ReplyRetrievalEnabledIntegrationTest 1; ai-service test_fewshot 8,
test_retrieval_eval 7 (ai-service 90 → 105 passed).
Verify: backend `[INFO] Tests run: 1082, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; ai-service `105 passed`.

#### U4.7 review (fresh subagent): 3 MAJOR. Dispositions (`bdc6072`)
- FIXED MAJOR 1 (blind = file exists): blind only for dataset `replies-v2`; dataset sha256 recorded. Revert proof
  (`blind = True`) → `test_a_dataset_that_is_not_the_blind_one_never_adopts` FAILED.
- FIXED MAJOR 2 (different scored rows): KEEP_DISABLED when either system has provider errors or the scored rows differ.
- FIXED MAJOR 3 (contamination): `similar()` searches only `mend.reply.retrieval.dataset-version` (default replies-v1-dev),
  DISTINCT text; import refuses `*-test` / `replies-v2`; the harness refuses examples that equal an evaluation text, rows
  without examples, and duplicate texts. Revert proof (no dataset filter) → `queryConstructionIsSafe:103`.
- FIXED MINOR 4 (fail closed per row), 5 (retrieval failure → `reply_retrieval_failed`, parse without examples, no attempt
  used; test), 6 (v2 prompt: `input:` JSON string + `output:` in the required shape; reply as JSON string after its own marker;
  `ensure_ascii=False`), 7 (OR query from quoted lexemes; ties by text), 8 (store SYNTHETIC-only until consent capture),
  9 (tests: special characters, determinism, harness ADOPT path with a stubbed model; export tool noted TBD), NIT 10
  (real calendar date, only with PROMISE_TO_PAY), 11 (sha256 + SYNTHETIC label in the harness report).
ai-service 105 → 110 passed; backend retrieval tests 4 + 2.

### U4.8 M3 backlog
Commits: `0e14d4d` (a, b), `044b176` (c), tenant-guard registration commit.
- (a) `LegacyReclassificationService` + `POST /api/v1/internal/merchants/{id}/reclassify-legacy` (SYSTEM_ADMIN): a NEW
  deterministic `classification_results` row from the stored webhook (event_id NULL, evidence links the legacy row);
  legacy rows untouched; no state change, no intent; NO_WEBHOOK campaigns keep failing closed. A later human-triggered
  re-evaluation sees the deterministic class (test: UNKNOWN_CLASSIFICATION before, ALLOWED + one intent after).
- (b) DECISION U4.8-b: "resolve without collecting" is a distinct action (`POST /api/v1/reviews/{id}/resolve-no-collect`),
  allowed only for CUSTOMER_DISPUTE / CUSTOMER_SAYS_PAID reviews (DTO `kind = RESOLVE_NO_COLLECT`): pending intents
  cancelled, campaign CANCELLED, review CLOSED, audited; refused while an intent is in flight (an issued link cannot be
  withdrawn). Approve keeps its meaning (resume collection), so money semantics are unchanged; reply review texts now
  point to the new action.
- (c) Analytics: "Recovered on a MEND Action (observed)" (was "Total Revenue Recovered"); late capture, attribution
  unknown, SYNTHETIC and amount unknown shown apart; Holdout Experiment panel (`GET /api/v1/analytics/experiment`:
  operational arm counts + causal figures computed by MetricsReport from the latest VALID snapshot restricted to the
  merchant's pseudonym, else TBD). Customer page: SYNTHETIC card. Actions page: resolve-without-collecting button.
Tests: backend M3BacklogIntegrationTest 5, ExperimentReportIntegrationTest 2, tenant-guard table +2 methods (116 dynamic);
frontend analytics page 3 (new), actions +1, customer +1 (62 → 67).
Revert proofs: frontend old label + no resolve button → `2 failed, 65 passed`; (a)/(b) covered by the assertions above.
Verify: backend `[INFO] Tests run: 1098, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; frontend `Tests: 67 passed`,
`✓ Compiled successfully`, lint `✖ 1 problem (0 errors, 1 warning)`; ai-service `110 passed`; migrations.sh BUILD SUCCESS.
