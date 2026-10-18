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
