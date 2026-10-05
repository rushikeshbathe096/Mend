# U9 — MEND-side errors defer; MANUAL_REVIEW goes to the review queue

## TASK
1. Classify `ProviderActionOutcome` for retry: `DEFER` = NotSent, Rejected(RATE_LIMITED), Rejected(AUTHENTICATION with code NO_CREDENTIALS_FOR_MERCHANT), Rejected(LOCAL_PRECONDITION AMOUNT_UNRESOLVED), NotSupported(PROVIDER_UNAVAILABLE-type MEND causes); `TERMINAL` = provider VALIDATION rejections, NotSupported(RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2) — debit stays NotSupported but is routed per step 3, not exhausted.
2. DEFER: intent → READY via a fenced transition from EXECUTING (`deferFromExecuting(token, scheduledAt)`), `scheduled_at = now + backoff(execution_attempts)`, campaign EXECUTING → ACTION_PENDING (`RETRY_DEFERRED`), audit `EXECUTION_DEFERRED` with code. After `mend.execution.max-deferrals` (5): intent FAILED, campaign FAILED → ELIGIBLE, review item `OPERATOR_ACTION_REQUIRED:<code>`. Never EXHAUSTED for DEFER codes.
3. MANUAL_REVIEW:
   - `createActionIntentFromCompliance` with strategy MANUAL_REVIEW → `HumanApprovalService.createReview(...)`, return null, no intent.
   - Approval (OD-1): the executed action is REQUEST_CUSTOMER_ACTION. Implement as an explicit override parameter on the creation path used by `approveReview`; compliance is still re-run.
   - Legacy MANUAL_REVIEW intents reaching the executor (or RETRY_PAYMENT NotSupported in razorpay mode) → FAILED + campaign FAILED → ELIGIBLE + review item, not EXHAUSTED.
4. Update tests that asserted EXHAUSTED for these codes (with `// M2 behaviour change` comments).

## FILES TO TOUCH
Executor finalize mapping, transitions, `ActionIntentService`, `HumanApprovalService` (approval override only), tests, `application.properties`.

## ACCEPTANCE CRITERIA
No DEFER code leads to EXHAUSTED (test per code). MANUAL_REVIEW creates zero intents until approved; approval yields a link intent.

## DO NOT IMPLEMENT
AI changes, new review UI, debit enablement.
