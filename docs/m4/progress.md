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
