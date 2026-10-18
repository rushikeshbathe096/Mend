# M4 experiment run (U4.6) — **SYNTHETIC**

Everything on this page is SYNTHETIC: generated failures, an assumed customer/provider outcome model and the replay
simulator. None of it is revenue or evidence about real customers (AGENTS.md invariant 20). The real-data section is
**TBD** until a pilot (M6).

## How it was produced (reproducible)

```
REBUILD=1 scripts/eval/simulate.sh --out docs/m4/results --snapshots <scratch dir> --seed 20261018 --size 10000 --holdout-bp 2000
cp <scratch dir>/sim-20261018-10000/manifest.json docs/m4/results/sim-20261018-10000-manifest.json
```

- Population: `SyntheticGenerator` (seed 20261018, 10 000 failures, documented reason and amount weights, window
  2026-01-01 + 28 days).
- Policy: the production `FailureTaxonomy`, `ActionPolicy`, `RecoveryStrategyEngine`, `ComplianceEngine` and
  `HoldoutAssigner`. Holdout 0.2 (2 000 bp). Merchant config = the entity defaults: contact window 24 h, no strategy
  restriction. This is not the value `MerchantConfigService` writes on a merchant's first config load; see OOS-4 in
  `docs/m4/progress.md`.
- Outcomes: `OutcomeModel.defaults()` (assumptions; the table is in the policies file). Horizon 14 days, every invoice
  mature.
- Snapshot: written by the U4.3 `SnapshotWriter` (validator: VALID). Its sha256 is `9c7e614225e2764b…`; the manifest is
  in `sim-20261018-10000-manifest.json`. The 30 MB snapshot files are not committed. The command above regenerates them
  and the report byte for byte; `ReplaySimulatorTest.committedRunIsReproducible` checks the committed checksum and report
  JSON, and `cliIsReproducibleAndLabelled` checks every output file, the snapshot directory included.
- Report: the U4.4 `MetricsReport` from those files only.

Files:
- `sim-20261018-10000.md` / `.json`: the full metrics report (every figure with n, label, status).
- `sim-20261018-10000-policies.md`: decision B vs A and the retry-timing comparison.

The same chain also runs on the real database pipeline: `SyntheticExperimentEndToEndTest` (150 failures, mock provider,
isolated test database). The path is stored failure webhook → classification (taxonomy) → holdout → strategy →
compliance → intent → scheduler (polled until no intent is claimable; every intent is dispatched) → provider webhooks
(late captures with the real amounts, paid links) → snapshot → report. Only audit history is moved back in time; see the
test. The test asserts that the report's per-arm "k of n" equals the database's per-arm RECOVERED counts, that the
MEND-attributed recoveries match, and that CONTROL has no intent.

## Headline (SYNTHETIC, overall, holdout 2 000 bp, n = 10 000 mature invoices)

| figure | value | 95 % interval |
|---|---|---|
| recovery rate TREATMENT (n 7 989) | 18.54 % | 17.70 % … 19.41 % |
| recovery rate CONTROL (n 2 011) | 10.29 % | 9.04 % … 11.70 % |
| incremental recovery rate (Newcombe, n 10 000) | 8.24 pp | 6.61 pp … 9.77 pp |
| true incremental rate under the model (computed on the same draws, all 10 000 eligible) | 7.94 pp | — |
| incremental amount per eligible invoice (bootstrap, n 10 000) | 71.24 INR | 48.87 … 92.79 INR |
| sample-ratio mismatch p | 0.783316 (CONTROL 2 011 of 10 000) | — |
| contact rate CONTROL | 0.00 % | — |
| cost per incremental recovered rupee | TBD (no cost model) | — |

The report is **INTERIM — not for decision**, because no sample size was pre-registered. The comparison is causal
*within the simulation*. Its interval (6.61 … 9.77 pp) contains the true effect computed under the model (7.94 pp,
policies file). That validates the estimation pipeline, not MEND.

## Error analysis by failure class (SYNTHETIC; EXPLORATORY, no multiplicity control)

| failure class | n TREATMENT | rate TREATMENT | n CONTROL | rate CONTROL | incremental (95 % CI) |
|---|---|---|---|---|---|
| AUTHENTICATION_FAILURE | 773 | 37.52 % | 199 | 15.58 % | 21.94 pp (15.34 … 27.53) |
| BANK_TECHNICAL | 691 | 23.01 % | 155 | 27.10 % | -4.09 pp (-12.15 … 3.09) |
| CUSTOMER_DECLINED | 608 | 15.79 % | 170 | 3.53 % | 12.26 pp (7.48 … 15.91) |
| GATEWAY_ERROR | 463 | 22.89 % | 127 | 24.41 % | -1.52 pp (-10.42 … 6.26) |
| INSTRUMENT_INVALID | 553 | 20.98 % | 129 | 2.33 % | 18.65 pp (13.31 … 22.55) |
| INSUFFICIENT_FUNDS | 2 272 | 9.64 % | 557 | 9.52 % | 0.12 pp (-2.83 … 2.64) |
| LIMIT_EXCEEDED | 390 | 25.64 % | 92 | 4.35 % | 21.29 pp (13.78 … 26.56) |
| MANDATE_INVALID | 238 | 13.45 % | 61 | 1.64 % | 11.81 pp (3.79 … 16.91) |
| UNCLASSIFIED | 2 001 | 18.14 % | 521 | 6.91 % | 11.23 pp (8.24 … 13.80) |

Where treatment did and did not help:
- **No effect: INSUFFICIENT_FUNDS, BANK_TECHNICAL, GATEWAY_ERROR.** These classes get a retry strategy (RETRY_LATER /
  RETRY_IMMEDIATELY). Debits are not wired, so in production a retry is dispatched and the provider answers
  NotSupported (`RECURRING_DEBIT_NOT_ENABLED`). Production then routes the campaign to human review. The simulator models
  the dispatch and the missing capture, but not that review item. Their intervals contain 0.
  This is a property of the current product, not only of the model. The retry classes are 42.65 % of the failures
  (4 265 = 2 829 + 846 + 590), and 3 426 TREATMENT invoices (2 272 + 691 + 463) received a retry that cannot recover
  money. Recorded as OOS-3 in `docs/m4/progress.md` for M5: wire debits, or send these classes a link.
- **"Contact rate TREATMENT 99.14 %" overstates customer contact.** It counts every DISPATCH. A NotSupported retry
  dispatch reaches no customer, which is OOS-2 (the snapshot's contact definition).
- **Helped: every class that gets a payment link.** The size of each effect is the model's link-payment assumption
  minus late-capture crowding-out, by construction.
- **UNCLASSIFIED volume: 2 522 of 10 000** (25.2 %, from the documented-but-unmapped `card_declined` / `payment_failed`
  weights). Under owner decision B they get a link. The policy comparison puts B − A at +23.59 INR per failure
  (19.73 … 27.79, Monte Carlo interval only, n = 10 000) and +275 recovered failures.
- **LEGACY_DECISION dispatch blocks: 0** in the simulation. The generated population has no pre-M3 decisions. The real
  count is TBD until a snapshot of real data exists (the system-health figure reports it).
- System health: post-M4 campaigns without an assignment 0, CONTROL contacted 0, sample ratio consistent with 0.2.

## Real data

**TBD.** No pilot has run. A real report needs a merchant with `holdout_rate_bp > 0` (recommended 2 000, ADR-004 §2),
Razorpay live mode (test mode is SYNTHETIC by rule U4.3-f), a pre-registered sample size from the design-doc power
table, and `scripts/eval/snapshot.sh` + `scripts/eval/report.sh`. Planned for the M6 pilot.
