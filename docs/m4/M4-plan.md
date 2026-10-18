# M4 — implementation plan and acceptance checklist

Branch `m4/evaluation` from local `main` @ `9fec57f` (tag `m3-followups`). Baseline: backend 971 / 0 / 0 / 0 (U4.0
run), frontend 62, ai-service 90. Each unit: plan in `docs/m4/progress.md` → small commits → verify (backend
`env -u JWT_SECRET ./mvnw clean test`, frontend test + build + lint, ai-service pytest, `scripts/ci/migrations.sh`) →
revert proof per fix / behaviour change → fresh-subagent review (AGENTS.md + unit spec + diff) → fix → log.

## Units (refined)

- **U4.1 Holdout assignment.**
  - V22 adds `merchant_config.holdout_rate_bp` (INT NOT NULL DEFAULT 0, CHECK 0–5000) and
    `merchant_experiment_salts` (merchant_id, purpose ASSIGNMENT|EVAL_ID, salt_version, salt BYTEA, created_at; PK
    (merchant_id, purpose, salt_version)).
  - V22 also adds `holdout_assignments` (campaign_id PK, merchant_id, arm, bucket, holdout_rate_bp, salt_version,
    hash_version, assigned_at), with an immutability trigger (UPDATE/DELETE/TRUNCATE).
  - V22 adds a BEFORE INSERT trigger on `action_intents` that rejects a CONTROL campaign. There are no FKs, so the log
    outlives test cleanup and merchant deletion (ids only).
  - `com.mend.experiment.HoldoutAssigner` (pure Java).
  - `ExperimentAssignmentService.assignAtFirstEligibility` is called inside `CampaignLifecycleService.transitionState`
    on the CLASSIFIED → ELIGIBLE edge only, in the same transaction.
  - The orchestrator and `processClassificationResult` skip CONTROL (audit `HOLDOUT_CONTROL_NO_ACTION`).
    `ActionIntentService` refuses CONTROL (`HoldoutControlException`).
  - Merchant config API: `holdoutRate` read and update, validated, audited.
  - Tests: determinism, 100 000 seeded keys within tolerance, CONTROL gets no intent and no provider call (incl. manual
    start, review approval), immutability, no re-assignment on re-entry, compliance unchanged for TREATMENT.
- **U4.2 Decision log.**
  - V23 `decision_log` (id, merchant_id, campaign_id, intent_id, decision_point, chosen, alternatives JSONB,
    propensity_bp, taxonomy/strategy/compliance/assignment versions, decided_at), append-only trigger.
  - `DecisionLogService.append` is called from the assignment, strategy persist, compliance persist and executor
    dispatch / abandon paths, in their own transactions.
  - Tests: exactly one row per decision point per path; UPDATE/DELETE/TRUNCATE rejected.
- **U4.3 Evaluation snapshots.**
  - V24 `eval_snapshots` (immutable; status VALID|INVALID; errors JSONB).
  - `EvalSnapshotService` (on-demand `POST /api/v1/internal/eval/snapshots`, SYSTEM_ADMIN; `@Scheduled` nightly, off by
    default).
  - Pure `com.mend.evaluation.snapshot` (row schema, canonical JSONL / CSV writer, sha256, validator).
  - `scripts/eval/snapshot.sh` (curl).
  - Tests: checksum stability, PII absence, each defect detected (negative / fractional amount, RECOVERED without
    attribution, missing assignment, schema).
- **U4.4 Metrics + intervals.**
  - Pure `com.mend.evaluation.stats` (Wilson, Newcombe, seeded bootstrap, median, sample size) and `MetricsReport`
    (snapshot files → `docs/m4/results/<id>.md` + `.json`).
  - CLI `scripts/eval/report.sh` (main class run from the backend jar via Spring Boot's PropertiesLauncher, no new
    plugin).
  - Tests: hand-computed values, labels, insufficient-data cases, TBD cost.
- **U4.5 Replay simulator.**
  - `com.mend.evaluation.sim`: a seeded synthetic generator (documented distributions) and a replay through the
    production taxonomy / strategy / compliance / assigner instances.
  - A seeded outcome model (customer pays link; late capture), policy variants: owner decision B vs A for UNCLASSIFIED
    and retry timing.
  - Output: snapshot-format rows labelled SYNTHETIC, in memory, never written to a table.
  - Tests: same seed → same result; the policy classes are production types; no DataSource in the simulator.
- **U4.6 Synthetic experiment run.**
  - Population (seeded), holdout 0.2, the full report through U4.3 (validator + checksum + files) and U4.4.
  - A policy A vs B comparison and an error analysis per failure class, plus UNCLASSIFIED volume and LEGACY_DECISION
    counts.
  - An end-to-end integration test through the real database pipeline (small n, isolated test DB).
  - `docs/m4/results/` committed (SYNTHETIC); the real-data section is TBD (M6 pilot).
- **U4.7 Retrieval-augmented reply parsing (gated).**
  - V25 `reply_label_examples` (source SYNTHETIC|CONSENTED, text, intent, promised_date, dataset_version, tsvector
    generated + GIN).
  - The backend retrieves the k best examples (ts_rank) when `mend.reply.retrieval.enabled=true` (default false) and
    sends them. ai-service prompt `parse-reply-v2-fewshot` is used only when examples are sent.
  - The eval harness gets a `--examples` mode. Adoption rule as in ADR-004 §8. With no key or no `replies-v2`: TBD,
    flag off.
- **U4.8 M3 backlog.**
  - (a) `LegacyReclassificationService`: new deterministic classification rows from the stored webhook (old rows
    untouched; audited). It never acts: a human still approves.
  - (b) Review kind `RESOLVE_NO_COLLECT` for CUSTOMER_DISPUTE / CUSTOMER_SAYS_PAID: resolving it closes the campaign
    without collecting (no intent; pending intents cancelled; audited).
  - (c) Analytics and customer UI show amount-unknown, late-capture, attribution-unknown, SYNTHETIC and the
    treatment/control figures (labelled).
- **U4.9 Close-out.** Full suites, `M4-summary.md` (≤ 15 lines), ADR-004 Accepted with evidence, checklist ticked,
  README "Evaluation", AGENTS.md milestone → "M4 complete — next: M5 (needs design pack)".

## §51 acceptance checklist (derived from the M4 rules; the brief itself is not in the repository)

- [ ] 1. Holdout assignment deterministic, seeded-distribution test, stored immutably at first eligibility, never re-assigned. — U4.1
- [ ] 2. CONTROL creates no intent and no provider call (service + DB trigger); compliance / opt-out / reviews unchanged. — U4.1
- [ ] 3. Per-merchant holdout rate, default 0 (DECISION), pilot recommendation in ADR-004. — U4.1
- [ ] 4. Decision log append-only (DB-enforced), one row per decision point, propensities at decision time. — U4.2
- [ ] 5. Snapshots: immutable, versioned, checksummed, PII-free, validated (INVALID recorded, never fixed), idempotent checksum. — U4.3
- [ ] 6. Metrics from snapshots only; Newcombe / Wilson / seeded bootstrap; labels MEASURED/SYNTHETIC/TBD with n; insufficient data for small n. — U4.4
- [ ] 7. Simulator uses production policy classes, seeded and reproducible, writes no production table. — U4.5
- [ ] 8. Synthetic experiment report end-to-end + policy A vs B + error analysis; real data TBD. — U4.6
- [ ] 9. Retrieval experiment gated: PostgreSQL FTS only, SYNTHETIC/consented store, flag off unless the adoption rule is met; metrics TBD without measurement. — U4.7
- [ ] 10. M3 backlog (a) reclassification into new rows, (b) RESOLVE_NO_COLLECT, (c) honest UI figures. — U4.8
- [ ] 11. No V1–V21 edit; migrations never rewrite data; fresh-database migrate + validate OK. — all
- [ ] 12. No new infrastructure or dependency; test counts never drop; suites green. — all
- [ ] 13. ADR-004 Accepted with evidence; M4-summary.md; README Evaluation; AGENTS.md milestone + invariants 19–25. — U4.9
