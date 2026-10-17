# Bug registry (pre-M3 bug sweep)

Branch `bugfix/pre-m3` from `main` @ `e591173` (merge of `cleanup/pre-m3`). Baseline: `env -u JWT_SECRET ./mvnw test` →
`[INFO] Tests run: 683, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

Sources read: `AGENTS.md`, `docs/audit/unused-code-audit.md` (UA), `docs/audit/cleanup-report.md` (CR; "OOS" = its out-of-scope
findings, "FU" = follow-up findings), `docs/m2/m2-progress.md` (MP), `docs/m2/M2-summary.md` (MS), the reconciliation run
(RC), and a code grep for `TODO|FIXME|HACK|XXX` over `backend/src`, `frontend/src`, `scripts`, `ai-service` (excluding
`node_modules`, `.venv`): **0 hits**, so no entries come from it.

Items already fixed by the cleanup (`cleanup/pre-m3`, now on `main`) are listed at the end for traceability only.

Classifications: BUG, SECURITY, TEST-RELIABILITY, QUALITY, M3-OWNED, M5-OWNED, ACCEPTED-TRADEOFF.

## Open issues

| id | source | description | class | decision |
|---|---|---|---|---|
| BR-01 | UA §5 "Membership / tenant check pasted into services"; CR scope note | The `currentUser != null && !isSystemAdmin && !isMemberOfMerchant` rule is copied into 12 services (`ActionIntentService`, `AnalyticsQueryService`, `AuditService`, `CampaignLifecycleService`, `CampaignQueryService`, `ComplianceService`, `HumanApprovalService`, `MerchantConfigService`, `MerchantMemberService`, `RecoveryActionQueryService`, `RecoveryStrategyService`, `WebhookQueryService`). Most copies **let a null user through**; `HumanApprovalService` rejects it; `MerchantConfigService` / `MerchantMemberService` would throw an NPE. | SECURITY | **Fix, item 1** (`fix: single tenant guard`). |
| BR-02 | MP U8 "Not changed"; MS Deviations | Late capture of the original payment before any MEND action ends the campaign CANCELLED instead of RECOVERED: the state machine has no CREATED/CLASSIFIED/ELIGIBLE/SCHEDULED → RECOVERED edge. | BUG | **Fix, item 2** (edges gated on `ORIGINAL_PAYMENT_LATE_CAPTURE`). |
| BR-03 | MP U8 + U9 review; MS M3 inputs | `HumanApprovalService.createReview` returns the existing PENDING review, so a later reason (e.g. `CAPTURED_ON_TERMINAL_CAMPAIGN` after `DUPLICATE_PAYMENT_RISK`) reaches only the audit log, not the reviewer. | BUG | **Fix, item 3** (append reason with timestamp + code). |
| BR-04 | MP U9 review MINOR; MS Deviations; ADR-002 | Deferrals and SAFE_RETRY share `execution_attempts` (cap 5 vs 3): e.g. 2 deferrals + an Unknown outcome leave no SAFE_RETRY. | BUG | **Fix, item 4** (`defer_attempts`, V17). |
| BR-05 | CR OOS 4 | Mock-mode `SyntheticCaptured` sets RECOVERED with no attribution, so mock/demo recoveries show as "attribution unknown" (and 0 MEND recoveries). | BUG | **Fix, item 5** (`SYNTHETIC` attribution, migration, reported separately). |
| BR-06 | CR OOS 7; MP U6/U7 (`parkOtherRows`, shared DB) | 15 test classes call `deleteAll()` on shared tables in the run's shared database (wipes other classes' rows; order-dependent). | TEST-RELIABILITY | **Fix, item 6**, except the two M3-owned classes (BR-06b). |
| BR-06b | CR OOS 7 | `ClassificationServiceTest` and `AiRecoveryAgentIntegrationTest` also `deleteAll()` shared tables (incl. `merchants` in the latter). They test M3-owned classes (`ClassificationService`, `RecoveryOrchestratorService`). | M3-OWNED | Not touched (AI path is out of scope). M3 must scope their cleanup. |
| BR-07 | CR OOS 7 | `debug=true` in `application-test.properties` (DEBUG auto-config report; Flyway at TRACE), tens of MB of log per run. | TEST-RELIABILITY | **Fix, item 6**. |
| BR-08 | CR OOS 7 | `DevDataInitializer` (`mend.dev.seed=true` in the test profile) runs in every test context and holds one connection / transaction ≈5 s (Hikari leak warning). | TEST-RELIABILITY | **Fix, item 6**. |
| BR-09 | RC (reconciliation, `docs/audit/cleanup-report.md` "Branch reconciliation") | `JwtSecretConfigurationTest`'s two default-profile failure cases pass vacuously-wrong when `JWT_SECRET` is exported: the runner's `JWT_SECRET=` override is outranked by the OS environment, the context starts, the test fails. | TEST-RELIABILITY | **Fix, item 6b** (isolate from the OS environment like `JwtSecretStartupTest`). |
| BR-10 | CR OOS 6 | Frontend `npm run lint`: 64 problems, 53 errors (35 `no-explicit-any`, 17 `react-hooks/set-state-in-effect`, 1 `react-hooks/purity`), 11 warnings. | QUALITY | **Fix, item 7**. |
| BR-11 | CR FU 5; RC | `webhookStatus.test.ts` reads a backend source file (assumes the monorepo layout). | ACCEPTED-TRADEOFF | Keep (owner: do not touch). |
| BR-12 | MP U4 review | Captured evidence for a terminal (e.g. FAILED) intent is audited (`CAPTURED_EVIDENCE_NOT_APPLIED`), but what to do with that real money (refund / review / recover) is undecided. | BUG (open owner decision) | Not in this sweep: needs an owner decision on money handling (stop condition). |
| BR-13 | MP U3 review | `cancelIfExecutable` keeps the pre-M2 rule (PENDING/SCHEDULED/READY only); ADR §4 says campaign cancellation cancels any non-terminal intent. Only caller today is review rejection (no in-flight intent there). | QUALITY | Not in this sweep; decide with campaign-cancel semantics (M3 design pack). |
| BR-14 | MP U9 review | An intent that expires (24 h) while deferred leaves its campaign ACTION_PENDING (same as any intent expiring before execution; pre-existing). | BUG (low) | Not in this sweep; recorded for the M3 design pack (expiry sweep). |
| BR-15 | MS M3 inputs | Approval re-runs compliance, so low-confidence MANUAL_REVIEW items stay blocked after approval. | M3-OWNED | Owner/M3 decision (confidence thresholds are M3-owned). |
| BR-16 | MS M3 inputs | Due-claim query Q1: seq scan of `campaigns` + full sort of READY rows (27.458 ms at 100k). | M5-OWNED | Performance; revisit with metrics (M5). |
| BR-17 | MS M3 inputs | `invoice_key` derivation not yet checked against a real `payment.failed` recording. | M3-OWNED | Needs a test-mode recording (owner). |
| BR-18 | MS Still deferred | Debit wiring / order-based debit reconciliation; payment-link cancel call (docs-verified, needs `--cancel-link` recording). | M3-OWNED | Deferred by ADR-002. |
| BR-19 | MS Still deferred; UA LATER-MILESTONE | Metrics / alerts registration. | M5-OWNED | Deferred. |
| BR-20 | MP U9 NIT | Orchestrator OD-1 change is not covered end to end (M2 context mocks the orchestrator). | M3-OWNED | M3 tests. |
| BR-21 | MP U3 | Demo "ambiguous" scenario narrates a dispatch that is simulated. | ACCEPTED-TRADEOFF | Demo is dev-only (hygiene); text says SIMULATED. |
| BR-22 | MP U8 | V15 header does not mention that `ADD CONSTRAINT CHECK` scans under ACCESS EXCLUSIVE. | ACCEPTED-TRADEOFF | V15 is applied; never edited (invariant 10). Runbook note. |
| BR-23 | UA §2 | "Phase N" test names and provider tests in test package `com.mend.client`. | QUALITY | Not in this sweep (rename-only; owner decision). |
| BR-24 | CR OOS 8 | Jackson 2 stays at test scope for 4 integration tests; `RedisWebhookEventPublisher` keeps an unneeded `@Primary`. | QUALITY | Not in this sweep (no behaviour impact). |
| BR-25 | CR FU 4 | `Badge` still has a `DUPLICATE` case (also a demo-scenario id). | ACCEPTED-TRADEOFF | Harmless; keep. |
| BR-26 | UA §3, §4, §6, §7 | ai-service deps, benchmarks, tests; agent endpoints; `AgentDecisionStack`; `AgentDecisionQueryController` (its own `merchantId` query-param membership check, line 52, is **not** moved to the tenant guard in item 1). | M3-OWNED | Not touched. |
| BR-27 | UA §4 | Backend endpoints with no frontend caller (`/auth/me`, member management, …). | ACCEPTED-TRADEOFF | Public API surface; keep. |
| BR-28 | CR OOS 9; RC | Local state: stale `mend_test_*` DBs (24 h sweep), a prunable foreign worktree under `/tmp/claude-1000/…/b1176de1…/scratchpad/main-wt`. | QUALITY (local) | Left alone (not repository state). |
| BR-29 | this sweep (setup) | `main` @ `e591173` is local only: `origin/main` is still `b669205`, and no `pre-m3-clean` tag exists locally or on `origin`. | QUALITY (process) | Reported to the owner; nothing pushed (task rule). |

## Already fixed before this sweep (traceability)

| source | item | fixed in |
|---|---|---|
| UA §1, §2, §4, §5, §6 | unused enums / repository methods, legacy publish chain, legacy status values + CHECKs (V16), payment-link id helper, analytics amounts (strict parser, no fabricated 1000.00), README links, frontend M2 states / demo page / `COMPLETED` check | cleanup Groups 1–9 |
| CR OOS 1, 2, 3, 5; FU 1, 2, 3 | compliance in-flight states, JWT default secret, customer summary MEND-only + unknown amounts, webhook filter, fresh-campaign attempt number, customer UI | cleanup follow-ups 1 and 2 |
| RC | `JwtSecretStartupTest`, customer list assertions, cleanup plan / report sections | reconcile commits |
