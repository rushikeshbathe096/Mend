# DESIGN DOC — M1 Provider Correctness

Companion evidence: `M1-provider-correctness.md` (section numbers below refer to it).

## Problem

MEND's only Razorpay adapter calls three endpoints that do not exist in Razorpay's API, sends a payment-link body Razorpay documents as invalid, and treats any HTTP 2xx as recovered money. Provider timeouts and 5xx are recorded as failures although the provider may have acted. No test exercises real Razorpay behaviour.

## Current behaviour

See §2–§3. Summary: `RETRY_PAYMENT` → invented endpoints; `REQUEST_CUSTOMER_ACTION` → `POST /v1/payment_links` with no amount and three undocumented fields; 2xx → intent `SUCCEEDED` + campaign `RECOVERED`; timeout/5xx → `FAILED`; missing response `id` → fabricated reference.

## Evidence

- Official docs, fetched 2026-10-05 (§4, findings DOC #1–#12).
- Source inspection (§1–§3).
- Recorded test-mode behaviour: **none yet** (U0).

## Requirements

1. Only documented Razorpay operations are called.
2. Business logic never sees Razorpay paths, JSON, or status strings.
3. Every provider interaction returns one of: `Accepted`, `Rejected`, `NotSent`, `OutcomeUnknown` (writes) or `Found`, `NotFound`, `Unavailable` (reads).
4. Money only becomes "recovered" on `captured` evidence.
5. Amounts are `long` minor units end-to-end in the provider path.
6. A lost response for a state-changing call has a documented lookup path.
7. Re-executing the same intent cannot create a second provider resource (links: provider-enforced; debits: not executed in M1).
8. Unsupported operations fail locally, without network calls.

## Invariants (M1-relevant subset of brief §6)

- 6.1 integer minor units — provider DTOs carry `long amount`.
- 6.3 provider evidence is financial truth — `Accepted` ≠ recovered.
- 6.4 compliance re-check before execution — untouched; stays in `prepareExecution`.
- 6.5 auditability — new audit types: `PROVIDER_REQUEST_ACCEPTED`, `PROVIDER_REQUEST_REJECTED`, `PROVIDER_REQUEST_NOT_SENT`, `PROVIDER_OUTCOME_UNKNOWN`, each with intent, merchant, attempt key, provider reference (if any), HTTP status, provider error code.
- 6.6 untrusted input — provider responses parsed into strict records; unknown status strings map to `UNRECOGNIZED`, never to success.

## Options

| | Option | Pros | Cons |
|---|---|---|---|
| A | Patch endpoints inside the existing `executeAction` switch | Smallest diff | Keeps SUCCESS/FAILURE/ERROR collapse; keeps Razorpay logic entangled; no place for lookups |
| B | **New domain port `PaymentProvider` with typed operations + two-axis results; Razorpay adapter split into transport/classifier/mapper** | Correct semantics; testable per layer; domain independent of Razorpay | Touches executor + tests; more classes |
| C | Use Razorpay's official Java SDK | Less HTTP code | SDK throws a generic exception type — loses the NotSent/Unknown distinction we need; hides exact wire format from contract tests; extra dependency |
| D | Implement the full recurring debit flow now | Delivers RETRY_PAYMENT | Domain lacks token/customer/contact/amount; async outcome is unsafe before M2 fencing + reconciliation |

## Decision

**B.** Payment links + evidence reads are implemented and wired in M1. Recurring debit is implemented at adapter/contract level only if U0 obtains recordings (U8); it is **not wired** to execution until M2 exits. `RETRY_PAYMENT` in Razorpay mode → local `Rejected(NOT_SUPPORTED)`.

## Architecture

See §7. Packages: `com.mend.provider` (port, results, value objects), `com.mend.provider.razorpay` (+ `dto`), `com.mend.provider.mock`. `ActionExecutionService` keeps prepare → call → finalize; only the call site and the outcome mapping change.

Executor mapping after U6:

| ActionType | Provider call | Result | Intent | Campaign |
|---|---|---|---|---|
| REQUEST_CUSTOMER_ACTION | createPaymentLink | Accepted | EXECUTING; `responseReference = plink id` | stays EXECUTING (recovery only via evidence) |
| REQUEST_CUSTOMER_ACTION | createPaymentLink | Rejected / NotSent | FAILED | existing FAILED path |
| REQUEST_CUSTOMER_ACTION | createPaymentLink | OutcomeUnknown | EXECUTING | stays EXECUTING |
| RETRY_PAYMENT | none (razorpay mode) | Rejected(NOT_SUPPORTED) | FAILED | existing FAILED path |
| MANUAL_REVIEW | none | unchanged | unchanged | unchanged |

`RECOVERED` is reached only through reconciliation on `captured` evidence (U7).

## Failure modes

| Failure | Detection | Recovery | Audit | Metric (reserved, emitted in M5) |
|---|---|---|---|---|
| Provider timeout after send | `HttpTimeoutException` | intent left EXECUTING; M2 reconciler looks up by reference | `PROVIDER_OUTCOME_UNKNOWN` | `mend_provider_requests_total{op,result="unknown"}` |
| Provider 5xx | status ≥ 500 | same | same | same |
| Connection refused | `ConnectException` | intent FAILED; nothing sent | `PROVIDER_REQUEST_NOT_SENT` | `{result="not_sent"}` |
| Bad credentials | 401 | FAILED; operator fixes config | `PROVIDER_REQUEST_REJECTED` code AUTH | `{result="rejected",kind="auth"}` |
| Duplicate execution of same intent | 400 on create + lookup Found | treat as Accepted(existing) | `PROVIDER_REQUEST_ACCEPTED` (dedup=true) | `{dedup="true"}` |
| Malformed 2xx | parse failure / missing id | OutcomeUnknown | `PROVIDER_OUTCOME_UNKNOWN` | unknown |
| Unknown provider status string | mapper | `UNRECOGNIZED`, never success | WARN log | `mend_provider_unrecognized_status_total` |
| Amount unresolvable | local | Rejected(LOCAL_PRECONDITION), no call | `PROVIDER_REQUEST_REJECTED` | `{kind="local_precondition"}` |
| Lease expiry during call (debits) | — | **not addressed in M1** — debits disabled | — | M2 |

## Security implications

- Remove undocumented headers; log no secrets (Authorization header never logged; existing `sanitize` kept for messages).
- `RazorpayCredentialsResolver` port. M1 implementation: env keys + **`razorpay.single-tenant-merchant-id`**. If the intent's merchant ≠ configured merchant → `Rejected(AUTHENTICATION, "NO_CREDENTIALS_FOR_MERCHANT")`, no call. This stops merchant B's recovery running on merchant A's Razorpay account. Encrypted per-merchant secrets: M5.
- Customer PII: M1 sends no customer object (`notify:false`). See §8 "Customer contact".
- Recording script refuses live keys and redacts PII before writing fixtures.

## Testing strategy

§11 (recorded fixtures → doc-derived fixtures → socket-level failure injection), plus: executor mapping tests for every row above; reconciliation tests proving `authorized`/`active` are not recovery; mock provider tests; legacy test rewrites with documented reasons.

## Metrics

M1 emits a structured log line per provider call: `event=provider_call op=<op> result=<Accepted|Rejected|NotSent|OutcomeUnknown> kind=<...> http_status=<n> latency_ms=<n> merchant_id intent_id attempt_key`. Metric names above are **reserved**; registering them (Micrometer/Actuator) is M5. All numbers: **TBD**.

| Metric | Type | Value |
|---|---|---|
| Provider p95 latency (create link, test mode) | MEASURED | TBD (U0 records latency) |
| Unknown-outcome rate | MEASURED | TBD |

## Tradeoffs

- More types than a single `executeAction` — accepted, because the collapse of outcome kinds *is* the bug.
- `RETRY_PAYMENT` disappears in Razorpay mode for now — accepted; an invented endpoint is worse than an honest "not supported".
- Lookup-before-reject on every 400 costs one extra GET on genuine validation errors — negligible, and only on the error path.
- Not using the official SDK means owning ~300 lines of HTTP code — accepted for exact wire control and failure classification.

## Exit criteria

Brief §48 items 1–13, mapped to units in `M1-implementation-plan.md`.
