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
   - The assignment row stores the arm, bucket, rate, salt version, hash version, source and timestamp. It is written
     in the transaction that moves the campaign CLASSIFIED → ELIGIBLE and is immutable (trigger). A campaign that
     re-enters ELIGIBLE keeps its assignment. Campaigns that were eligible before M4 have none (UNASSIGNED, not in the
     experiment).
   - One arm per invoice: a later campaign of an invoice that already has an assignment copies that stored arm, bucket
     and rate (`source = INVOICE_REUSE`) instead of hashing under the current rate, under a per-invoice advisory lock.
     The invoice is also the analysis unit. **DECISION U4.1-e:** reuse applies only while the merchant's holdout is on.
     At rate 0 the merchant has ended the experiment, so a new campaign of a former CONTROL invoice is hashed at rate 0
     (TREATMENT) and is not withheld indefinitely.
2. **Holdout rate per merchant.** `merchant_config.holdout_rate_bp`, an integer from 0 to 5 000 (0.5).
   **DECISION: default 0 (off).** Withholding recovery is a revenue decision that only the merchant can make, so it is
   opt-in. The cap of 0.5 keeps MEND from withholding recovery from most of a merchant's campaigns.
   **Pilot recommendation: 0.2** (2 000 bp). At a 10 % control rate it detects a 5-point lift with about 1 900 eligible
   campaigns (power 0.8, α 0.05), about 60 % of what 0.1 needs. Use 0.1 if the merchant will not risk more.
3. **CONTROL withholds recovery contact only.**
   - A CONTROL campaign gets no strategy, no intent and no provider call. It stays ELIGIBLE and is tracked.
   - A late original capture still recovers it, attributed `ORIGINAL_PAYMENT_LATE_CAPTURE`.
   - Original-payment events, replies, opt-outs, disputes and safety reviews are handled as for any campaign.
   - Intent creation for a CONTROL campaign is refused in `ActionIntentService` (`HOLDOUT_CONTROL`, HTTP 409) and by a
     database trigger on `action_intents`. A refusal changes no state, so it writes no audit row (one written in the
     throwing transaction would roll back). Review approval for CONTROL is refused; the review stays PENDING.
   - Compliance and opt-out apply unchanged to TREATMENT. Nothing that compliance or a customer requires is ever withheld.
4. **Append-only decision log.** One `decision_log` row per decision point, in the decision's transaction:
   - `ASSIGNMENT`: chosen TREATMENT or CONTROL; alternatives are both arms (TREATMENT alone when the rate is 0); the
     propensity is the arm's probability (`10 000 − rate` or `rate`, in basis points).
   - `STRATEGY`: the chosen strategy; alternatives are the strategies the merchant's config allows; propensity
     10 000 bp (deterministic).
   - `COMPLIANCE`: ALLOWED, or BLOCKED with its reason; alternatives are both; propensity 10 000 bp.
   - `DISPATCH`: DISPATCHED, BLOCKED (compliance, legacy decision or opt-out at the pre-dispatch check) or EXPIRED;
     propensity 10 000 bp.

   Every row carries the versions in force: taxonomy (of the campaign's latest classification), strategy and compliance
   (the deterministic engines'; a DISPATCH row carries the version of the stored decision that authorized it; a
   COMPLIANCE row for a human-approved override strategy has no strategy version) and assignment (null = UNASSIGNED).
   One intent can have several DISPATCH rows (a deferred intent is dispatched again), so only ASSIGNMENT and
   STRATEGY / COMPLIANCE (per persisted decision) are unique. The expiry sweeper and the demo's recorded dispatch log too. UPDATE, DELETE and TRUNCATE are
   rejected by trigger. Probabilities are integers in basis points, so there is no floating point in stored data.
5. **Snapshots, not live tables.**
   - `eval_snapshots` stores id, schema version, created_at, window, as_of, filters, row count, sha256 and status
     VALID/INVALID with the validation errors. The rows are immutable.
   - Files (`campaigns.jsonl`, `decisions.jsonl`, `campaigns.csv`, `manifest.json`) go under
     `mend.eval.snapshot.dir`.
   - Ids are hashed per merchant. There is no PII: no contact, no reply text, no raw payment or invoice id.
   - Point in time: every field is rebuilt as of `as_of` from append-only, timestamped sources (audit state
     transitions and capture-evidence codes, decision_log, assignments, intents' `dispatched_at`, webhooks'
     `received_at`), never from current state. Content is ordered deterministically and hashed without the creation
     time, so the same window and `as_of` give the same sha256 even after later activity.
   - Outcome: provider capture evidence within a fixed horizon H (default 14 days) after first eligibility, read the
     same way in both arms (RECOVERED transition, or the capture-on-terminal audit codes). Only mature invoices
     (`eligible_at + H ≤ as_of`) are analysed.
   - Validation never repairs data. A failure is recorded and the snapshot is INVALID.
6. **Statistics.**
   - Difference of recovery rates: Newcombe's hybrid score interval (method 10, Wilson-based, 95 %). It is well
     behaved near 0 and 1 and at small n, where the Wald interval fails.
   - Money per eligible invoice: a percentile bootstrap, stratified by arm, seeded (seed recorded), default 2 000
     resamples (never fewer). Sums are exact long minor units; means and bounds are exact and rounded HALF_EVEN to the
     paisa.
   - Time: cumulative recovery by day 1, 3, 7 and 14 per arm (ITT denominator, Newcombe for the difference). The median
     time among recovered invoices is descriptive only (it conditions on the outcome).
   - Minimum n: 30 per arm for rate intervals, 100 per arm for money (flagged "unstable" below 300), 10 recoveries per
     arm for the median. Below that, the figure is "insufficient data" with its n.
   - Strata: one figure per holdout-rate stratum, no pooling. Sample-ratio mismatch: chi-square at p < 0.001.
   - Reports before the pre-registered sample are labelled INTERIM — not for decision.
   - Every figure is labelled MEASURED, SYNTHETIC or TBD.
   - Cost comes from a configured cost model. The decision metric is cost per *incremental* recovered rupee (cost ÷ the
     T−C money difference). Cost per observed recovered rupee is reported as non-causal. An unknown cost makes both TBD.
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
- Only recoveries MEND can observe count (MEND links, late original captures), in both arms. Money is gross of refunds
  (net is TBD).
- Interference: a customer with invoices in both arms may be influenced across arms. This is measured per snapshot; the
  fallback is randomizing by customer.
- Erasure: the append-only tables need an audited, migration-gated purge per merchant (M5-owned).
- No off-policy evaluation of strategies from production logs: every propensity is 1.0. Strategy comparisons are
  SYNTHETIC (simulator).
- Merchants without a holdout get TREATMENT rows with propensity 1.0. That is consistent data, but not an experiment.

## Revisit conditions
A merchant needs a stratified or blocked design (e.g. by amount); a stochastic policy is proposed (enables off-policy
evaluation); pilot data shows a carry-over between arms (e.g. a customer with invoices in both arms); a real outcome
channel other than provider evidence appears.

## Evidence
To be filled at U4.9 (commits, tests, results).
