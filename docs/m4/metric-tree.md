# M4 metric tree (§60)

Every leaf is computed from an evaluation snapshot (never live tables), per merchant and overall, and labelled
**MEASURED** (real provider evidence), **SYNTHETIC** (simulator or generated data) or **TBD** (not computed or not
computable), always with its n. The unit is an *eligible invoice* (campaigns sharing an `invoice_key` collapse to one;
assigned at first eligibility). Outcome = provider capture evidence within the horizon H (default 14 days) after
first eligibility; only mature invoices count. The arms are
TREATMENT and CONTROL. UNASSIGNED (pre-M4) campaigns are reported apart and never enter a causal figure.

```
North star: incremental money recovered by MEND  (TREATMENT − CONTROL, per eligible invoice, × eligible TREATMENT n)
│
├── Money recovered (minor units, exact sums)
│   ├── recovered amount per eligible invoice, by arm              bootstrap 95 % CI (seeded, ≥ 2 000)
│   ├── incremental recovered amount per eligible invoice           bootstrap CI of the difference
│   ├── amount-unknown count (excluded from every sum)              count only
│   └── attribution breakdown: MEND / LATE_CAPTURE / SYNTHETIC / unknown (count + amount)
│
├── Recovery rate
│   ├── rate(TREATMENT), rate(CONTROL)                               Wilson 95 % CI
│   └── incremental recovery rate = rate(T) − rate(C)                Newcombe hybrid score 95 % CI
│
├── Time to recovery
│   ├── cumulative recovery by day 1 / 3 / 7 / 14, by arm (ITT)      Wilson CI per arm, Newcombe CI of the difference
│   └── median time among recovered, by arm                          DESCRIPTIVE ONLY (conditions on the outcome); ≥ 10 recovered
│
├── Cost per recovery
│   ├── cost per incremental recovered rupee = cost / (T − C money difference)   decision metric; TBD when unset
│   ├── cost per observed recovered rupee                                          NON-CAUSAL; TBD when unset
│   └── contact rate = campaigns with a dispatched intent / eligible campaigns, by arm (CONTROL must be 0)
│
├── Breakdowns (each of the above, where n allows)
│   ├── by failure class (taxonomy class; UNCLASSIFIED and legacy separately)
│   ├── by channel (action type: REQUEST_CUSTOMER_ACTION payment link; debit = not wired, TBD)
│   └── by strategy (TREATMENT's chosen strategy; CONTROL has none → by class only)
│
└── System health (data quality of the experiment itself)
    ├── assignment coverage: post-M4 eligible campaigns with an assignment (must be 100 %)
    ├── sample-ratio mismatch: chi-square of arm counts vs configured rate, per stratum, flagged at p < 0.001
    ├── interference: customers (hashed) with invoices in both arms
    ├── immature invoices (eligible_at + H > as_of), excluded from outcomes
    ├── CONTROL contamination: CONTROL campaigns with an intent or a dispatch (must be 0)
    ├── decision-log completeness: one ASSIGNMENT row per assignment; STRATEGY / COMPLIANCE / DISPATCH per intent path
    ├── snapshot validation status (VALID / INVALID + defects)
    ├── UNCLASSIFIED volume and LEGACY_DECISION count
    └── RECOVERED rows without attribution (defect)
```

Minimum n (ADR-004 §6): rate intervals need n ≥ 30 per arm, money intervals n ≥ 100 (flagged unstable below 300); the
median needs ≥ 10 recoveries per arm. Figures are per holdout-rate stratum; reports before the pre-registered sample
are INTERIM — not for decision. Below
that the figure is "insufficient data (n = …)". Sample-size planning: `M4-design-doc.md` § Metrics.
