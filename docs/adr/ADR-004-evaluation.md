# ADR-004 — Evaluation: randomized holdout, decision log, snapshots, honest metrics

Status: **Proposed** (M4 U4.0, 2026-10-18).
Milestone: M4 · Depends on: ADR-001, ADR-002, ADR-003

## Context
MEND reports observed recovery only. A failed payment can be paid anyway (late capture of the original payment, or the
customer paying by themselves), so "recovered while MEND was active" does not show that MEND caused it. The policy is
deterministic, so logs alone cannot support a counterfactual. Design: `docs/m4/M4-design-doc.md`.

## Decision
1. **Per-merchant randomized holdout, decided at first eligibility.**
   - The unit is the campaign's `invoice_key` within its merchant.
   - `HMAC-SHA256(key = merchant salt, message = invoice_key)`, first 8 bytes as an unsigned 64-bit integer,
     `bucket = value mod 10 000`. CONTROL iff `bucket < holdout_rate_bp`. Hash version
     `hmac-sha256-u64-mod10000-v1`.
   - The salt is 32 random bytes per merchant, generated at the first assignment and stored with a salt version.
   - The assignment row stores the arm, bucket, rate, salt version, hash version and timestamp. It is written in the
     transaction that moves the campaign CLASSIFIED → ELIGIBLE and is immutable (trigger). A campaign that re-enters
     ELIGIBLE keeps its assignment. Campaigns that were eligible before M4 have none (UNASSIGNED, not in the experiment).
2. **Holdout rate per merchant.** `merchant_config.holdout_rate_bp`, an integer from 0 to 5 000 (0.5).
   **DECISION: default 0 (off).** Withholding recovery is a revenue decision that only the merchant can make, so it is
   opt-in. The cap of 0.5 keeps MEND from withholding recovery from most of a merchant's campaigns.
   **Pilot recommendation: 0.2** (2 000 bp). At a 10 % control rate it detects a 5-point lift with about 1 900 eligible
   campaigns (power 0.8, α 0.05), about 60 % of what 0.1 needs. Use 0.1 if the merchant will not risk more.
3. **CONTROL withholds recovery contact only.**
   - A CONTROL campaign gets no strategy, no intent and no provider call. It stays ELIGIBLE and is tracked.
   - A late original capture still recovers it, attributed `ORIGINAL_PAYMENT_LATE_CAPTURE`.
   - Original-payment events, replies, opt-outs, disputes and safety reviews are handled as for any campaign.
   - Intent creation for a CONTROL campaign is refused in `ActionIntentService` (`HOLDOUT_CONTROL`, audited) and by a
     database trigger on `action_intents`.
   - Compliance and opt-out apply unchanged to TREATMENT. Nothing that compliance or a customer requires is ever withheld.
4. **Append-only decision log.** One `decision_log` row per decision point, in the decision's transaction:
   - `ASSIGNMENT`: chosen TREATMENT or CONTROL; alternatives are both arms (TREATMENT alone when the rate is 0); the
     propensity is the arm's probability (`10 000 − rate` or `rate`, in basis points).
   - `STRATEGY`: the chosen strategy; alternatives are the strategies the merchant's config allows; propensity 1.0.
   - `COMPLIANCE`: ALLOWED, or BLOCKED with its reason; alternatives are both; propensity 1.0.
   - `DISPATCH`: DISPATCHED, BLOCKED (compliance, legacy decision or opt-out at the pre-dispatch check) or EXPIRED;
     propensity 1.0.

   Every row carries the taxonomy, strategy, compliance and assignment versions. UPDATE, DELETE and TRUNCATE are
   rejected by trigger. Probabilities are integers in basis points, so there is no floating point in stored data.
5. **Snapshots, not live tables.**
   - `eval_snapshots` stores id, schema version, created_at, window, as_of, filters, row count, sha256 and status
     VALID/INVALID with the validation errors. The rows are immutable.
   - Files (`campaigns.jsonl`, `decisions.jsonl`, `campaigns.csv`, `manifest.json`) go under
     `mend.eval.snapshot.dir`.
   - Ids are hashed per merchant. There is no PII: no contact, no reply text, no raw payment or invoice id.
   - Content is ordered deterministically and hashed without the creation time, so the same window and `as_of` give
     the same sha256.
   - Validation never repairs data. A failure is recorded and the snapshot is INVALID.
6. **Statistics.**
   - Difference of recovery rates: Newcombe's hybrid score interval (method 10, Wilson-based, 95 %). It is well
     behaved near 0 and 1 and at small n, where the Wald interval fails.
   - Money per eligible campaign and median time to recovery: a percentile bootstrap, stratified by arm, seeded
     (seed recorded), default 2 000 resamples (never fewer). Money is long minor units throughout.
   - Minimum n: 30 per arm for rate and money intervals, 10 recoveries per arm for the median. Below that, the figure
     is "insufficient data" with its n.
   - Every figure is labelled MEASURED, SYNTHETIC or TBD.
   - Cost per recovered rupee comes from a configured cost model. An unknown cost makes it TBD.
7. **Simulator.** An in-memory replay of event streams through the production `FailureTaxonomy`,
   `RecoveryStrategyEngine`, `ComplianceEngine` and `HoldoutAssigner`. It uses a seeded outcome model, labels all
   output SYNTHETIC and writes to no table.
8. **Retrieval-augmented reply parsing is a gated experiment.**
   - The example store is PostgreSQL full-text (no vector DB) and holds only SYNTHETIC or consented examples.
   - The flag is off by default.
   - It is adopted only when macro-F1 improves on the blind `replies-v2` set without a rise in the invalid-output
     rate. Without a key or `replies-v2`, the metrics are TBD and the flag stays off.

## Why
Randomization is the only design under which "treatment − control" is an unbiased estimate of what MEND adds, whatever
seasonality, mix or customer behaviour do. Hash-based assignment is reproducible and auditable without storing a random
draw, and it is stable per invoice. Logging propensities at decision time makes later analysis honest: nothing is
reconstructed afterwards. Snapshots make every report reproducible from a checksum.

## Consequences
- Revenue cost: CONTROL campaigns lose the recovery MEND would have caused. The expected cost is
  holdout rate × eligible campaigns × the incremental rate × the average amount. That is unknown until measured, which is
  why the default is off.
- ITT dilution: compliance blocks and opt-outs in TREATMENT lower the measured effect. This is the business-relevant
  effect, and per-protocol figures are not reported as causal.
- Only recoveries MEND can observe count (MEND links, late original captures), in both arms.
- No off-policy evaluation of strategies from production logs: every propensity is 1.0. Strategy comparisons are
  SYNTHETIC (simulator).
- Merchants without a holdout get TREATMENT rows with propensity 1.0. That is consistent data, but not an experiment.

## Revisit conditions
A merchant needs a stratified or blocked design (e.g. by amount); a stochastic policy is proposed (enables off-policy
evaluation); pilot data shows a carry-over between arms (e.g. a customer with invoices in both arms); a real outcome
channel other than provider evidence appears.

## Evidence
To be filled at U4.9 (commits, tests, results).
