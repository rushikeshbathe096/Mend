# DESIGN DOC — M4 Evaluation: does MEND cause recovery?

Status: accepted for implementation by the orchestrator (U4.0). ADR: ADR-004. Plan: `docs/m4/M4-plan.md`. Metric tree:
`docs/m4/metric-tree.md`. The brief (§27, §28, §51, §54, §60, §61) is not in the repository (standing DECISION from
M3): §54 headings follow the M2/M3 design docs, the §51 checklist is derived from the M4 rules in the task prompt and
listed in `M4-plan.md`, §60 is the metric tree.

## Problem
MEND reports "recovered" campaigns, but it cannot say whether MEND *caused* any of them. A customer whose payment failed
may pay anyway: the original payment can be captured late, or the customer can retry by themselves. Today every number is
observed recovery. No campaign is withheld, so no comparison exists, and nothing records the probability with which a
decision was taken. Snapshots for offline analysis do not exist either: analytics read live tables and change under the
reader.

## Current behaviour
- Every eligible campaign gets the deterministic policy: taxonomy → strategy → compliance → intent
  (`CampaignLifecycleService.processClassificationResult`, `RecoveryOrchestratorService`). No randomization.
- Recovery evidence: `campaigns.recovery_attribution` (`MEND`, `ORIGINAL_PAYMENT_LATE_CAPTURE`, `SYNTHETIC`; NULL before
  V15). The analytics count only `MEND` as recovered and report the rest separately (`AnalyticsQueryService`).
- Decisions are persisted as current-state rows (`recovery_decisions`, `compliance_decisions`) plus free-text audit
  rows. Nothing stores the alternatives or the probability of the chosen action.
- Amounts come from the stored webhook through the strict minor-unit parser (`WebhookAmountResolver.extractMoneyMinor`);
  unknown amounts are counted, never defaulted.
- Reply parsing (the one AI task) has a SYNTHETIC eval set (`docs/m3/eval/replies-v1-*`) and a keyword baseline; model
  metrics are TBD (no key).

## Evidence
- Code: files above; ADR-001..003; M3 summary (backend 971 / 0 / 0 / 0 at tag `m3-followups`, run again at U4.0:
  `[INFO] Tests run: 971, Failures: 0, Errors: 0, Skipped: 0`).
- No real pilot data exists. Every result in M4 is SYNTHETIC or TBD; the real-data section stays TBD until a pilot (M6).

## Requirements
1. A randomized holdout per merchant: at first eligibility a campaign is assigned TREATMENT or CONTROL from
   `hash(merchant salt, invoice_key)`; CONTROL gets no recovery contact; the assignment is stored once and never changed.
2. Every decision point (assignment, strategy, compliance, dispatch) appends one row to an immutable decision log with
   the chosen action, the eligible alternatives, the probability of the chosen action and the policy versions, in the
   decision's own transaction.
3. A snapshot pipeline (scheduled + on demand) writes immutable, checksummed, PII-free, validated evaluation datasets.
4. Metrics with confidence intervals computed from snapshots only, every number labelled MEASURED / SYNTHETIC / TBD with
   its n; small n → "insufficient data".
5. An offline replay simulator that runs the production policy classes with a seeded outcome model (SYNTHETIC).
6. A synthetic end-to-end experiment report and policy comparison; the real-data section is TBD.
7. A gated retrieval experiment for reply parsing, disabled by default, adopted only on measured improvement.
8. M3 backlog: legacy reclassification into new rows, a resolve-without-collecting review kind, honest UI figures.

## Invariants
AGENTS.md 1–18 unchanged. Added for M4 (AGENTS.md 19–25):
- 19. Observed recovery ≠ incremental recovery. A causal claim ("MEND recovered X more") comes only from a randomized
  TREATMENT vs CONTROL comparison.
- 20. Every reported number is labelled MEASURED (real provider evidence), SYNTHETIC (simulator / generated) or TBD, with
  its n. SYNTHETIC results are never presented as revenue. A number that was not computed is TBD, never estimated.
- 21. Assignment and propensities are logged at decision time, in the decision's transaction, and never rewritten
  (append-only, enforced by the database).
- 22. CONTROL withholds only recovery contact (no intent, no provider call). Compliance, opt-out, safety reviews and
  customer requests still apply. A campaign is assigned once, at its first eligibility, and never re-assigned.
- 23. Evaluation data is PII-free (hashed ids only), tenant-scoped, versioned and checksummed. Metrics read snapshots
  only, never live tables.
- 24. No new infrastructure (vector DB, Kafka, new services) and no new Maven / npm / pip dependency without asking.
- 25. The simulator never writes to production tables and runs the production policy classes, not copies.

## Options
1. **Before/after comparison** (recovery rate before MEND vs after). Confounded by seasonality, mix and merchant changes.
   Rejected: it cannot support a causal claim (invariant 19).
2. **Observational adjustment** (propensity models over a deterministic policy). The policy is deterministic (every
   propensity is 1.0), so there is no overlap and no off-policy estimate is possible. Rejected as the primary method;
   the decision log still records propensities so a future stochastic policy can be evaluated off-policy.
3. **Per-merchant randomized holdout, intention-to-treat analysis.** Chosen. Unbiased by construction; costs the
   recovery MEND would have caused on the held-out share (stated in ADR-004).
4. **Switchback / time-sliced experiments.** Needs volume per slice that a pilot will not have; carry-over between
   slices (a link stays payable for 72 h). Rejected for M4.

## Decision
Option 3, detailed in ADR-004:
- Unit of randomization: the campaign's `invoice_key` within a merchant (an invoice never gets both arms). Assignment:
  `HMAC-SHA256(merchant salt, invoice_key)` → first 8 bytes as an unsigned 64-bit integer → bucket = value mod 10 000 →
  CONTROL iff bucket < `holdout_rate_bp`. Default rate 0 (off); maximum 5 000 (0.5).
- Analysis: intention-to-treat. Outcome = the campaign reached RECOVERED with provider evidence (any attribution except
  SYNTHETIC in MEASURED reports) by the snapshot's `as_of`. Incremental recovery rate = rate(TREATMENT) − rate(CONTROL).
- Statistics: Newcombe hybrid score interval (Wilson-based) for the difference of proportions; seeded percentile
  bootstrap (default 2 000 resamples, stratified by arm) for money per eligible campaign and for median time to
  recovery; minimum sample sizes per figure (below).

## Architecture
```
webhook → taxonomy → CLASSIFIED → ELIGIBLE ──(same tx)── HoldoutAssigner → holdout_assignments + decision_log(ASSIGNMENT)
                                              │ TREATMENT → strategy (+decision_log STRATEGY) → compliance (+COMPLIANCE)
                                              │             → intent → executor dispatch fence (+DISPATCH)
                                              └ CONTROL   → no strategy, no intent, no contact; still tracked:
                                                            late capture / original-payment events recorded as before
EvalSnapshotService (scheduled + on demand) ── reads campaigns, assignments, decision_log, intents, audit, webhooks
   → validate → eval_snapshots row (sha256, VALID/INVALID) + campaigns.jsonl / decisions.jsonl / campaigns.csv / manifest
MetricsReport (CLI, snapshot files only) → docs/m4/results/<snapshot-id>.md + .json
ReplaySimulator (in memory) → FailureTaxonomy → RecoveryStrategyEngine → ComplianceEngine → HoldoutAssigner
   (production classes) + seeded outcome model → snapshot-format rows (SYNTHETIC) → same validator → MetricsReport
```
- `com.mend.experiment` (pure Java): `HoldoutAssigner`, `Arm`, `HoldoutAssignment`.
- `com.mend.evaluation` (pure Java): statistics (`Wilson`, `Newcombe`, `Bootstrap`, `SampleSize`), snapshot row schema,
  validator, metrics, report writer, simulator and synthetic generator. Spring-side services live in `com.mend.service`.
- Migrations V22+ only: new tables, nullable or defaulted columns, triggers. No V1–V21 edit, no data rewrite.
- CLI: `scripts/eval/snapshot.sh` (on-demand snapshot via the backend API), `scripts/eval/report.sh`,
  `scripts/eval/simulate.sh` (pure-Java mains run from the backend jar; no new plugin or dependency).
- Reply retrieval (U4.7): the backend owns the PostgreSQL full-text store (`reply_label_examples`, tsvector + GIN) and
  sends k examples to ai-service, which builds prompt version `parse-reply-v2-fewshot`. Flag off by default.

## Failure modes
| failure | behaviour |
|---|---|
| merchant has no salt yet | generated (SecureRandom, 32 bytes) and stored in the assignment transaction; concurrent creation resolved by the primary key, the loser re-reads |
| campaign re-enters ELIGIBLE (review, failure, legacy decision) | no new assignment: only the CLASSIFIED → ELIGIBLE edge assigns; the stored one is reused |
| holdout rate changed mid-experiment | existing assignments unchanged (immutable); new ones use the new rate and record it; the report stratifies by rate |
| CONTROL campaign reaches an intent path (manual start, review approval, demo) | refused in `ActionIntentService` (`HOLDOUT_CONTROL`) and by a database trigger on `action_intents` insert |
| CONTROL customer replies / opts out / disputes | stored and handled exactly as for TREATMENT (reply policy, opt-out, review); only recovery contact is withheld |
| late original capture on a CONTROL campaign | RECOVERED with `ORIGINAL_PAYMENT_LATE_CAPTURE` (bug sweep item 2 edges), counted as a CONTROL recovery |
| UPDATE / DELETE / TRUNCATE on decision_log, holdout_assignments, eval_snapshots | rejected by trigger |
| snapshot validation fails | snapshot stored with status INVALID and its error list; data never fixed; reports refuse INVALID snapshots unless forced, and then say so |
| amount missing or not a positive integer | `amount_minor` null, `amount_status UNKNOWN`; excluded from money sums, counted |
| n below the minimum | the figure is "insufficient data" with its n, no interval |
| cost not configured | cost per recovered rupee = TBD |

## Security implications
- No PII in snapshots: no customer contact, no reply text, no payment / invoice / customer ids. Campaign, intent and
  invoice references are `HMAC-SHA256(merchant eval salt, id)`; merchant references are SHA-256 pseudonyms. A test
  asserts the absence of every raw id and of reply text.
- Salts are random, generated at runtime, stored in the database (never in files, never logged).
- Snapshot files are written under a configured directory (default outside the repository); real snapshots are never
  committed. Only SYNTHETIC results are committed under `docs/m4/results/`.
- The on-demand snapshot endpoint is SYSTEM_ADMIN only and tenant-scoped by its filters.
- Retrieval examples are SYNTHETIC or consented only; the store has a `source` CHECK and is never filled from
  `customer_replies`.

## Testing strategy
- Assignment: determinism, distribution within tolerance over 100 000 seeded keys, CONTROL never creates an intent or a
  provider call (real PostgreSQL + mock provider spy), immutability (trigger), no re-assignment on re-entry.
- Decision log: each path writes exactly one row per decision point; rows immutable at the database level.
- Snapshot: checksum stability across re-runs, PII absence, each validation defect detected.
- Statistics: unit tests against hand-computed values (Wilson, Newcombe, bootstrap with a fixed seed, medians, sample size).
- Simulator: same seed → same result; the policy objects are the production classes (type identity, not copies).
- Retrieval: PostgreSQL FTS ranking test; ai-service prompt assembly tests; flag off by default.
- Every fix / behaviour change gets a revert proof; test counts never drop.

## Metrics
`docs/m4/metric-tree.md`. Minimum sample sizes: rate CI needs n ≥ 30 per arm; bootstrap money CI needs n ≥ 30 per arm;
median time to recovery needs ≥ 10 recovered per arm. Power (planning calculation, two-sided α = 0.05, power 0.8,
unpooled normal approximation, computed, assumed rates):

| control rate | treatment rate | holdout | eligible campaigns needed (total) | of which CONTROL |
|---|---|---|---|---|
| 0.10 | 0.15 | 0.1 | 3 271 | 328 |
| 0.10 | 0.15 | 0.2 | 1 914 | 383 |
| 0.10 | 0.15 | 0.5 | 1 366 | 683 |
| 0.20 | 0.25 | 0.2 | 3 248 | 650 |
| 0.10 | 0.12 | 0.2 | 11 421 | 2 285 |

(Totals rounded up; reproduced by `SampleSizeTest` in U4.4.) A pilot merchant needs roughly two thousand eligible
campaigns at holdout 0.2 to detect a 5-point lift from 10 %; smaller lifts need far more.

## Tradeoffs
- The holdout costs real recovery on CONTROL campaigns (the incremental effect on that share). Mitigation: default off,
  merchant opt-in, maximum 0.5, pilot recommendation 0.2 (ADR-004).
- ITT measures the effect of *being assigned* to MEND, diluted by compliance blocks and opt-outs in TREATMENT. That is
  the honest business question; per-protocol figures are not reported as causal.
- Recovery is observable only through provider evidence MEND sees (MEND links, late capture of the original payment). A
  customer who pays through another channel looks unrecovered in both arms (symmetric, so the difference is unbiased
  for the observable outcome, but levels are lower bounds).
- A deterministic policy has propensity 1.0 everywhere: off-policy comparison of strategies is impossible from logs.
  Policy comparison is done in the simulator and is SYNTHETIC.

## Exit criteria
§51 checklist in `docs/m4/M4-plan.md` all ticked with commits; backend / frontend / ai-service suites green with counts
not lower than at U4.0; migrations validate on an empty database; ADR-004 Accepted with evidence.
