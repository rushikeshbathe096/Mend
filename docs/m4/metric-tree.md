# M4 metric tree (§60)

Every leaf is computed from an evaluation snapshot (never live tables), per merchant and overall, and labelled
**MEASURED** (real provider evidence), **SYNTHETIC** (simulator or generated data) or **TBD** (not computed or not
computable), always with its n. The unit is an *eligible campaign* (assigned at its first eligibility). The arms are
TREATMENT and CONTROL. UNASSIGNED (pre-M4) campaigns are reported apart and never enter a causal figure.

```
North star: incremental money recovered by MEND  (TREATMENT − CONTROL, per eligible campaign, × eligible TREATMENT n)
│
├── Money recovered (minor units, exact sums)
│   ├── recovered amount per eligible campaign, by arm             bootstrap 95 % CI (seeded, ≥ 2 000)
│   ├── incremental recovered amount per eligible campaign          bootstrap CI of the difference
│   ├── amount-unknown count (excluded from every sum)              count only
│   └── attribution breakdown: MEND / LATE_CAPTURE / SYNTHETIC / unknown (count + amount)
│
├── Recovery rate
│   ├── rate(TREATMENT), rate(CONTROL)                               Wilson 95 % CI
│   └── incremental recovery rate = rate(T) − rate(C)                Newcombe hybrid score 95 % CI
│
├── Time to recovery (eligible_at → RECOVERED transition, recovered campaigns only)
│   └── median by arm                                                bootstrap 95 % CI; needs ≥ 10 recovered per arm
│
├── Cost per recovery
│   ├── cost per recovered rupee = configured cost × contacts / recovered amount   TBD when the cost model is unset
│   └── contact rate = campaigns with a dispatched intent / eligible campaigns, by arm (CONTROL must be 0)
│
├── Breakdowns (each of the above, where n allows)
│   ├── by failure class (taxonomy class; UNCLASSIFIED and legacy separately)
│   ├── by channel (action type: REQUEST_CUSTOMER_ACTION payment link; debit = not wired, TBD)
│   └── by strategy (TREATMENT's chosen strategy; CONTROL has none → by class only)
│
└── System health (data quality of the experiment itself)
    ├── assignment coverage: post-M4 eligible campaigns with an assignment (must be 100 %)
    ├── sample-ratio check: observed CONTROL share vs configured rate (Wilson CI must contain the rate)
    ├── CONTROL contamination: CONTROL campaigns with an intent or a dispatch (must be 0)
    ├── decision-log completeness: one ASSIGNMENT row per assignment; STRATEGY / COMPLIANCE / DISPATCH per intent path
    ├── snapshot validation status (VALID / INVALID + defects)
    ├── UNCLASSIFIED volume and LEGACY_DECISION count
    └── RECOVERED rows without attribution (defect)
```

Minimum n (ADR-004 §6): rate and money intervals need n ≥ 30 per arm; the median needs ≥ 10 recoveries per arm. Below
that the figure is "insufficient data (n = …)". Sample-size planning: `M4-design-doc.md` § Metrics.
