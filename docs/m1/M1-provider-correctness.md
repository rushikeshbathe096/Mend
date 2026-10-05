# M1 — PROVIDER CORRECTNESS

Status: **Investigation + design complete. No implementation yet.**
Documentation checked live on: 2026-10-05. Re-verify if more than ~3 months pass before implementation.
Repo snapshot: `Mend-main (3).zip`

Evidence labels used throughout:

| Label | Meaning |
|---|---|
| `DOC` | Stated in current official Razorpay documentation (URL listed in §4) |
| `CODE` | Observed in MEND source |
| `RECORDED` | Observed in a real Razorpay test-mode response captured by `scripts/record_razorpay_contracts.sh` |
| `NOT RECORDED` | No test-mode recording exists yet — **this is every operation today** |
| `AMBIGUOUS` | Docs silent, contradictory, or not yet confirmed by recording |

---

## 1. Current provider implementation

| Piece | Location | What it does |
|---|---|---|
| Port | `client/PaymentProviderClient.java` | One method `executeAction(PaymentExecutionRequest)`. Mixes "debit", "send link" and "unsupported" behind one call. |
| Razorpay adapter | `client/RazorpayPaymentProviderClient.java` | Chooses one of four endpoints from `ActionType` + which IDs are present; POSTs a generic `Map`; maps HTTP class → `SUCCESS/FAILURE/ERROR`. Active when `mend.payment.provider=razorpay`. |
| Mock adapter | `client/MockPaymentProviderClient.java` | Default (`matchIfMissing=true`). |
| Request DTO | `dto/payment/PaymentExecutionRequest.java` | merchantId, campaignId, intentId, paymentId, subscriptionId, actionType, attemptNumber, idempotencyKey. **No amount, currency, customer_id, token_id, order_id.** |
| Result DTO | `dto/payment/PaymentExecutionResult.java` | `SUCCESS / FAILURE / ERROR` + externalReference + message. |
| Callers | `service/ActionExecutionService.executeActionIntent` ← `ActionScheduler`, `CampaignController` (synchronous path) | Prepare (tx) → provider call (no tx) → finalize (tx). |
| Evidence consumer | `service/DefaultPaymentReconciliationService` | Maps webhook event type / payment status to success/failure. |
| Amount source | `service/WebhookAmountResolver` | Reads `amount` (paise) from the stored webhook payload and **converts to rupees as `BigDecimal`**. |
| Credentials | `RazorpayPaymentProviderClient.resolveCredentials` | Returns the global env key pair. `MerchantConfigRepository` is injected but **never used**; `merchantId` ignored. |
| Config | `application.properties` | base-url `https://api.razorpay.com`, key id/secret from env, connect 5 s / read 10 s. |

Side observation (not M1 scope): the adapter uses Jackson 2 (`com.fasterxml`) while reconciliation uses Jackson 3 (`tools.jackson`, Spring Boot 4). The new adapter should use the application's Jackson 3 mapper.

## 2. Every current Razorpay endpoint

| # | Operation (as coded) | Endpoint | Method | Body actually sent | Auth | Response handling | Error handling | Caller | Tests |
|---|---|---|---|---|---|---|---|---|---|
| E1 | Customer action → payment link | `/v1/payment_links` | POST | `{receipt:"rcpt_<n>", idempotency_key, payment_id?}` | Basic | 2xx → `SUCCESS`, ref = `id` | 4xx → `FAILURE`; timeout/5xx → `ERROR` | ActionExecutionService | `RazorpayPaymentProviderClientTest` (hand-written mocks) |
| E2 | Retry payment by id | `/v1/payments/{paymentId}/retry` | POST | same map | Basic | 2xx → `SUCCESS` | same | same | `RazorpayPaymentProviderClientTest`, `Phase20_RazorpayTestModeE2EValidationTest`, `RazorpayTestModeEndToEndIntegrationTest` |
| E3 | Charge subscription | `/v1/subscriptions/{subId}/charge` | POST | same map | Basic | 2xx → `SUCCESS` | same | same | none specific |
| E4 | Retry without ids | `/v1/payments/retry` | POST | same map | Basic | 2xx → `SUCCESS` | same | same | none specific |
| H1 | "Idempotency" headers | `X-Razorpay-Idempotency-Header`, `X-Idempotency-Key` | — | intent idempotency key | — | — | — | all POSTs | asserted in Phase20 test |

About the existing "test-mode" tests (`CODE`): `Phase20_RazorpayTestModeE2EValidationTest` and `RazorpayTestModeEndToEndIntegrationTest` use `MockRestServiceServer` with **hand-authored** JSON for `/v1/payments/{id}/retry`. They are not recordings. They prove the code calls the URL it was written to call, and nothing about Razorpay.

## 3. Current provider call flow

```text
ActionScheduler / CampaignController
  └─ ActionExecutionService.executeActionIntent(intentId, workerId)
       ├─ TX1 prepareExecution: intent must be CLAIMED; compliance re-check;
       │     campaign → EXECUTING; audit ACTION_EXECUTING
       │     (intent STAYS CLAIMED during the provider call ← M2 defect, see §10)
       ├─ no TX: paymentProviderClient.executeAction(request)
       │     ├─ REQUEST_CUSTOMER_ACTION           → POST /v1/payment_links
       │     ├─ RETRY_PAYMENT + paymentId         → POST /v1/payments/{id}/retry
       │     ├─ RETRY_PAYMENT + subscriptionId    → POST /v1/subscriptions/{id}/charge
       │     └─ RETRY_PAYMENT + neither           → POST /v1/payments/retry
       └─ TX2 finalizeExecution
             ├─ SUCCESS → intent SUCCEEDED, campaign RECOVERED     ← on ANY 2xx
             ├─ FAILURE → intent FAILED, campaign FAILED → EXHAUSTED
             └─ ERROR   → intent FAILED, campaign FAILED           ← includes read timeouts
```

## 4. Official Razorpay documentation verification

Sources fetched 2026-10-05:

- Recurring integration overview — https://razorpay.com/docs/payments/recurring-payments/overview/integrate/
- UPI subsequent payments (order + `/payments/create/recurring`) — https://razorpay-881012b3.mintlify.app/docs/api/payments/recurring-payments/upi/create-subsequent-payments.md
- India API Reference index (complete endpoint list) — https://razorpay-881012b3.mintlify.app/_llms/india/api-reference.md
- Create Standard Payment Link — https://razorpay-881012b3.mintlify.app/docs/api/payments/payment-links/create-standard.md
- Fetch All Standard Payment Links — https://razorpay-881012b3.mintlify.app/docs/api/payments/payment-links/fetch-all-standard.md
- Payments entity — https://razorpay-881012b3.mintlify.app/docs/api/payments/entity.md
- Understand Razorpay APIs (status codes, rate limiting) — https://razorpay-881012b3.mintlify.app/docs/api/understand.md
- Refund idempotency (what documented idempotency looks like) — https://razorpay-881012b3.mintlify.app/docs/api/refunds/normal-refunds-idempotent.md

(The mintlify host is the docs index that razorpay.com/docs links to via its `llms.txt`. If you prefer, open the same paths under `https://razorpay.com/docs/...`.)

Findings (`DOC`):

1. **No server-side "retry payment" API.** The Payments section of the API reference lists capture, fetch (+expanded variants), fetch all, fetch by order, card details, update. "Retry" in Razorpay docs refers to a Checkout option letting the *customer* retry in the browser, and to error-code advice ("retry after some time").
2. **No "charge subscription" API.** Subscriptions endpoints: plans, create, create link, fetch, cancel, update, pending update, cancel update, pause, resume, fetch invoices, link/delete offer. Subscriptions charge on Razorpay's schedule.
3. **Merchant-initiated debit = new order + recurring payment.** `POST /v1/orders` (amount ≤ mandate `max_amount`) then `POST /v1/payments/create/recurring` with `order_id`, `customer_id`, `token`, `recurring: true`, `amount` (must equal order amount), `currency` — and on the UPI page **`email` and `contact` are marked mandatory**. Prerequisite: token in `confirmed` state.
4. **Recurring outcome is asynchronous.** Documented success body is `{"razorpay_payment_id": "pay_..."}` (UPI page), not a payment entity. The final state arrives via `payment.captured` / `payment.failed`; for UPI this can take 24–36 h due to the pre-debit notification window. Razorpay says not to create another debit on the same token until the previous one reaches a terminal status.
5. **Razorpay may retry failed debits itself** unless the order carries a `notification` object; with it, Razorpay says it will *not* retry and the merchant must (UPI page; applicability to cards/eMandate is `AMBIGUOUS`). MEND retry and Razorpay retry could overlap.
6. **A `paid` order permits no further payment requests.**
7. **Payment links:** `POST /v1/payment_links`; `amount` (integer minor units, ≥ 100 for INR) required. `reference_id` must be unique per link (≤ 40 chars); duplicate → 400 "payment link creation with reference ID already attempted". **Unknown body fields → 400 "extra fields sent."** New links have status `created`; statuses `created, partially_paid, expired, cancelled, paid`. Test mode: max 30 links per business.
8. **Links are retrievable by `reference_id`:** `GET /v1/payment_links?reference_id=...`.
9. **Payment statuses:** `created, authorized, captured, refunded, failed`. Recurring docs also describe `late_authorized`. `authorized` is not money received — uncaptured authorisations are auto-refunded after the capture window.
10. **Idempotency headers are endpoint-specific.** Documented: `X-Refund-Idempotency` (refunds), Route transfer idempotency, RazorpayX payout idempotency. **None documented for orders, payment links, or `/payments/create/recurring`.** `X-Razorpay-Idempotency-Header` and `X-Idempotency-Key` appear nowhere in the docs I checked.
11. **HTTP semantics:** 400/401/404 client errors; 429 throttling (retry later with exponential backoff and jitter); 500/502/503/504 server errors. Error body `{"error":{"code","description","source","step","reason","metadata"}}`.
12. **Webhooks are at-least-once; Fetch APIs are the fallback and the source of truth when webhooks and your records disagree** (recurring integrate page).

## 5. Verdict

| Item | Verdict | Evidence | Action |
|---|---|---|---|
| E2 `POST /v1/payments/{id}/retry` | **INCORRECT** | DOC #1 · NOT RECORDED | Delete |
| E3 `POST /v1/subscriptions/{id}/charge` | **INCORRECT** | DOC #2 · NOT RECORDED | Delete |
| E4 `POST /v1/payments/retry` | **INCORRECT** | DOC #1 · NOT RECORDED | Delete |
| E1 endpoint `POST /v1/payment_links` | **CORRECT** | DOC #7 | Keep |
| E1 request body | **INCORRECT** | DOC #7 · CODE | Missing required `amount`; sends `receipt`, `idempotency_key`, `payment_id` which are not link fields → documented "extra fields" 400. **Every real link creation would currently be rejected.** |
| E1 response mapping (2xx → SUCCESS → RECOVERED) | **INCORRECT** | DOC #7 · CODE | A new link has `status: created`, `amount_paid: 0`. Creating a link is not recovering money (violates invariant 6.3). |
| H1 headers | **INCORRECT** | DOC #10 | Undocumented → false confidence. Remove. |
| Fabricated ref `"rzp_" + hash` when `id` missing | **INCORRECT** | CODE | Fabricated provider evidence → must be `OutcomeUnknown`. |
| Default `status = "success"` when missing | **INCORRECT** | CODE | Same. |
| Read timeout → ERROR → intent FAILED | **INCORRECT** | CODE · brief §14 | Request may have been processed. |
| 5xx → ERROR → intent FAILED | **INCORRECT** | DOC #11 | Server may have acted before failing. |
| Basic auth `key_id:key_secret` | **CORRECT** | DOC | Keep |
| Base URL + `/v1` prefix | **CORRECT** | DOC | Keep |
| Global env credentials, merchant ignored | **INCORRECT for multi-tenant** | CODE | M1: port + fail-closed guard. Real per-merchant secrets: M5. |
| Reconciliation: `authorized` = success | **INCORRECT** | DOC #9 | Only `captured` (or a `paid` link with a captured payment) is recovery. |
| Reconciliation: payload status `active` = success | **INCORRECT** | CODE | `active` is a token/subscription state, not money. |
| Amount as rupee `BigDecimal` | **INCORRECT vs invariant 6.1** | CODE | Resolve integer paise directly. |
| Order + `/payments/create/recurring` (brief's hypothesis) | **CORRECT per docs; test-mode behaviour AMBIGUOUS** | DOC #3–#6 · NOT RECORDED | MEND lacks `token_id`, `customer_id`, email/contact, amount → **not executable on the current data model.** UPI Autopay cannot be exercised in test mode at all (DOC). |
| Order `receipt` uniqueness enforced? | **AMBIGUOUS** | create-order: "unique identifier"; fetch-order: "has to be unique" | U0 records it. |
| Two `create/recurring` calls on the same *unpaid* order | **AMBIGUOUS** | Docs only cover `paid` | Never rely on it; M2 DB constraint prevents it. |
| Webhook name for a paid link (`payment_link.paid`) | **AMBIGUOUS** (not checked this session) | — | Confirm in U0 with one real test-mode payment. |
| Paid link status after payment | **RECORDED** (fixture `20`, 2026-10-05) | `status: paid`, `amount_paid` = amount, `payments[]` item with `payment_id` and `status: captured` | Matches U7's success rule (link PAID + payment CAPTURED). |
| Link notes copied onto the link's payment | **RECORDED** (fixture `21`, 2026-10-05) | Captured payment entity carries `notes.mend_intent_id` set only on the link; it also carries an `order_id` | Verifies owner decision D1's premise: a failed attempt's payment would correlate to the link intent, so FAILED must be a no-op for link intents. |
| `payment_link.paid` webhook name/shape | **AMBIGUOUS** (fixtures `22`–`24` not recorded) | — | Keep AMBIGUOUS until a delivered webhook body is recorded. |
| Exact text of duplicate-reference 400 | DOC · NOT RECORDED | DOC #7 | Never match on text alone (§9). |

## 6. MEND provider operations actually required

| MEND need | Razorpay operation | When | Why |
|---|---|---|---|
| Ask the customer to pay (`REQUEST_CUSTOMER_ACTION`) | `POST /v1/payment_links` | **M1** | Works for any failed payment, no mandate required, matches M6 Option A, customer-initiated so MEND cannot double-debit. |
| Recover a lost create-link response | `GET /v1/payment_links?reference_id=` | **M1** | Only documented way to learn whether the link exists. |
| Read link state | `GET /v1/payment_links/{id}` | **M1** | Evidence of `paid`. |
| Read payment evidence | `GET /v1/payments/{id}` | **M1** | Financial truth (`captured`); reconciliation fallback. |
| Merchant-initiated debit (`RETRY_PAYMENT`) | `POST /v1/orders` → `POST /v1/payments/create/recurring` | **Adapter only, conditional on recordings (U8). Not wired.** | Missing domain data; async outcome needs M2's reconciliation state + fencing first. |
| Read debit evidence | `GET /v1/orders/{id}/payments` | with U8 | Order is the anchor for an ambiguous debit. |
| Token state pre-check | `GET /v1/customers/{cust}/tokens/{token}` | M2/M3 | Required before any debit. |
| Subscription charge / payment retry | none exist | never | Not real APIs. |

**Behaviour change:** until M2 exits *and* the domain carries mandate data, `RETRY_PAYMENT` in Razorpay mode returns a local `Rejected(NOT_SUPPORTED)` with **zero HTTP calls**.

## 7. Correct provider adapter design

```text
Recovery domain (ActionExecutionService, reconciliation)
        │ depends on
        ▼
com.mend.provider.PaymentProvider            ← domain port, no Razorpay vocabulary
        ▲ implemented by
        ├── com.mend.provider.razorpay.RazorpayPaymentProvider
        │       ├── RazorpayHttp                  transport: auth, timeouts, JSON, exception → transport failure kind
        │       ├── RazorpayResponseClassifier    HTTP status + error body → RequestResult kind
        │       ├── RazorpayEvidenceMapper        Razorpay status strings → PaymentEvidenceStatus / PaymentLinkState
        │       ├── RazorpayCredentialsResolver   port; M1 impl = single-tenant env, fail-closed
        │       └── dto/*                         records mirroring documented JSON exactly
        └── com.mend.provider.mock.MockPaymentProvider   dev/test; outputs labelled SYNTHETIC
```

SRP: transport knows HTTP; classifier knows Razorpay error semantics; mapper knows Razorpay status vocabulary; the provider composes them per operation. DIP: the domain only imports `com.mend.provider`. OCP: a new operation or provider adds classes, not `if` branches.

`ActionExecutionService` keeps its three-phase shape (prepare tx → call outside tx → finalize tx) — it is sound. The old `PaymentProviderClient` and `PaymentExecutionRequest/Result` are removed in U6 once callers move.

HTTP client: use Spring `RestClient` with `JdkClientHttpRequestFactory` (connect timeout + read timeout). The JDK client's exception types let us tell "never sent" (`ConnectException`, `UnknownHostException`, `HttpConnectTimeoutException`) from "sent, no answer" (`HttpTimeoutException`, other `IOException`). `MockRestServiceServer` binds to `RestClient.Builder` for tests.

### Port

```java
package com.mend.provider;

public interface PaymentProvider {
    RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command);
    LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key);
    LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId);
    LookupResult<PaymentEvidence>     getPayment(MerchantRef merchant, String providerPaymentId);
    // U8 (conditional): createDebitOrder, initiateRecurringDebit, getOrderPayments
}
```

## 8. Request/response contract

### Two independent axes — never collapse them

1. **Request outcome** — did the provider accept *our command*?
2. **Payment evidence** — what happened to *the money*?

A link creation can be `Accepted` while the money stays unpaid for days.

```java
// Axis 1: state-changing request
public sealed interface RequestResult<T> {
    record Accepted<T>(T value) implements RequestResult<T> {}
    record Rejected<T>(RejectionKind kind, String providerCode, String providerReason, Integer httpStatus) implements RequestResult<T> {}
    record NotSent<T>(String cause) implements RequestResult<T> {}                    // provably never left MEND
    record OutcomeUnknown<T>(String cause, Integer httpStatus) implements RequestResult<T> {}
}
public enum RejectionKind { VALIDATION, AUTHENTICATION, NOT_SUPPORTED, RATE_LIMITED, DUPLICATE_REFERENCE, LOCAL_PRECONDITION }

// Axis 1 for reads (GETs are safe to retry)
public sealed interface LookupResult<T> {
    record Found<T>(T value) implements LookupResult<T> {}
    record NotFound<T>() implements LookupResult<T> {}
    record Unavailable<T>(String cause, Integer httpStatus) implements LookupResult<T> {}
}

// Axis 2: money
public enum PaymentEvidenceStatus {
    CAPTURED,                 // the ONLY status that establishes recovery
    AUTHORIZED_NOT_CAPTURED,  // not recovery; may auto-refund
    PENDING,                  // "created"
    FAILED,
    REFUNDED,
    UNRECOGNIZED              // unknown string → not recovered, logged at WARN
}
public record PaymentEvidence(String providerPaymentId, PaymentEvidenceStatus status, long amountMinor,
                              String currency, String orderId, String errorCode, String errorReason,
                              Instant observedAt) {}

public enum PaymentLinkState { CREATED, PARTIALLY_PAID, PAID, EXPIRED, CANCELLED, UNRECOGNIZED }
public record PaymentLinkSnapshot(String providerLinkId, ProviderAttemptKey referenceId, PaymentLinkState state,
                                  long amountMinor, long amountPaidMinor, String currency,
                                  String shortUrl, List<String> capturedPaymentIds) {}
```

Mapping to the brief's vocabulary: `REQUEST_REJECTED` = `Rejected`; `REQUEST_ACCEPTED` = `Accepted`; `UNKNOWN` = `OutcomeUnknown`; `PAYMENT_CAPTURED` / `PAYMENT_FAILED` = `PaymentEvidenceStatus.CAPTURED / FAILED`. `NotSent` is added because "connection refused" is provably safe to resend and should not be lumped with "unknown".

### Money

```java
public record MoneyMinor(long amountMinor, String currency) {
    // compact constructor: amountMinor > 0; M1 supports "INR" only
}
```
INR minimum for links is 100 paise (DOC #7) → checked locally → `Rejected(LOCAL_PRECONDITION)`, no HTTP call.

### Attempt key

```java
public record ProviderAttemptKey(String value) {
    // "mnd_" + 32 lowercase hex of the ActionIntent UUID (dashes removed) = 36 chars ≤ 40
    public static ProviderAttemptKey forIntent(UUID intentId) { ... }
}
```
Why not reuse `ActionIntent.idempotencyKey`? It is `intent:<campaignUuid>:attempt_N:<ACTION>` (~70 chars with colons) — over the 40-char `reference_id` limit. The intent UUID is already unique per intent, and the derived key is deterministic, so every re-execution of the same intent produces the same provider key.

### Razorpay wire DTOs (records, exact documented field names)

- `RazorpayCreatePaymentLinkRequest(amount, currency, reference_id, description, expire_by?, customer?, notify{sms,email}, reminder_enable, notes{mend_intent_id, mend_merchant_id})` — **no other fields**. Serialize with `NON_NULL`.
- `RazorpayPaymentLink` (id, amount, amount_paid, currency, reference_id, status, short_url, payments[], order_id).
- `RazorpayPaymentLinkList(payment_links[])`.
- `RazorpayPayment` (id, entity, amount, currency, status, order_id, captured, error_code, error_reason, notes).
- `RazorpayErrorEnvelope(error{code, description, source, step, reason, metadata})`.
- Unknown properties on *responses* are ignored (forward-compatible). Missing required response fields → `OutcomeUnknown`, never defaults.

`notes.mend_intent_id` is what makes webhook correlation by intent possible; today the adapter sends no notes at all, so `DefaultPaymentReconciliationService` strategy 2 can never match.

### Customer contact (open decision for you)

Payment link `customer` is optional. With `notify.sms/email = true` Razorpay sends the link, but only if contact/email are present. MEND stores only `customer_id_hash`; the original payment webhook payload (already stored raw) contains email/contact. Options: (a) pass contact from the stored payload at request time, Razorpay notifies; (b) send no customer data, MEND delivers `short_url` through its own channel (none exists yet). M1 default: **(b) `notify: false`, no customer object** — least data shared. Flip to (a) explicitly when you have a delivery need, and record it under DPDP review (M5).

## 9. Error / timeout semantics

| Situation | Detected as | RequestResult | Safe to resend same key? | Intent effect (U6) |
|---|---|---|---|---|
| Local validation fails (amount missing/<100, non-INR) | before HTTP | `Rejected(LOCAL_PRECONDITION)` | n/a | FAILED, no provider call |
| `RETRY_PAYMENT` in Razorpay mode (pre-M2) | before HTTP | `Rejected(NOT_SUPPORTED)` | n/a | FAILED (`PROVIDER_OPERATION_NOT_SUPPORTED`) |
| DNS failure / connection refused / connect timeout | `UnknownHostException`, `ConnectException`, `HttpConnectTimeoutException` | `NotSent` | Yes | FAILED (`PROVIDER_UNREACHABLE`) — conservative |
| Read timeout / reset after send | `HttpTimeoutException`, other `IOException` | `OutcomeUnknown` | Only after lookup | **EXECUTING (non-terminal)** + audit `PROVIDER_OUTCOME_UNKNOWN` |
| 2xx, body parses, required fields present | — | `Accepted` | — | link: EXECUTING, awaiting evidence (**not** SUCCEEDED) |
| 2xx, unparseable or missing `id` | — | `OutcomeUnknown` | Only after lookup | EXECUTING + audit |
| 400 on create link | — | **call `findPaymentLinkByReference` first**: Found → `Accepted(existing)`; NotFound → `Rejected(VALIDATION)`; Unavailable → `OutcomeUnknown` | — | per result |
| 400 elsewhere | — | `Rejected(VALIDATION)` | No | FAILED |
| 401 | — | `Rejected(AUTHENTICATION)` | No (config) | FAILED + ERROR log |
| 404 on POST | — | `Rejected(NOT_SUPPORTED)` | No | FAILED (wrong endpoint = config bug) |
| 404 on GET (or 400 "not a valid id" if fixture 13 shows Razorpay uses 400 for unknown ids) | — | `NotFound` | — | — |
| 409 on create | — | `OutcomeUnknown` | Only after lookup | EXECUTING |
| 429 | — | `Rejected(RATE_LIMITED)` | Yes, later, backoff + jitter | FAILED in M1 (re-scheduling is M2) |
| 5xx | — | `OutcomeUnknown` | Only after lookup | EXECUTING + audit |
| Anything else | — | `OutcomeUnknown` | Only after lookup | EXECUTING + audit |

Rules:

- The adapter **never auto-retries a POST.** Resending is a workflow decision that goes through lookup first. GETs may retry (max 2, exponential backoff with jitter).
- The adapter **never fabricates** an id, status, or amount.
- The 400 description text is logged, never trusted as the sole signal; lookup-by-reference is the authority.
- `EXECUTING` is not touched by `releaseExpiredClaims()` (`findExpiredClaims` selects only `CLAIMED`), so an unknown outcome cannot be silently recycled to READY. The full reconciliation loop is M2.

## 10. Idempotency semantics

**Identity of an attempt:** one `ActionIntent` ⇒ one `ProviderAttemptKey` ⇒ one provider resource (link `reference_id`; for U8 one order `receipt` + persisted `order_id`).

**Provider support:** no idempotency headers for these operations (DOC #10). What exists instead:

| Operation | Provider-side dedupe | Strength |
|---|---|---|
| Create payment link | `reference_id` unique; duplicate → 400 (DOC #7); lookup by `reference_id` (DOC #8) | Documented. **At most one link per intent** + lost-response recovery. Confirm by recording. |
| Create order | `receipt` possibly unique | `AMBIGUOUS`. If not enforced, MEND must persist `order_id` before calling `/payments/create/recurring` and never create a second order for the same intent. |
| Recurring payment | none; but a `paid` order rejects further payments (DOC #6); Razorpay asks for one debit in flight per token (DOC #4) | Partial. Concurrency on an *unpaid* order is `AMBIGUOUS`. |

**How MEND prevents duplicates:**

- Today: `action_intents.idempotency_key` UNIQUE, atomic claim, `campaign_attempts UNIQUE(campaign_id, attempt_number)`. **But** the claim can expire while the provider call is in flight and be returned to READY by `releaseExpiredClaims()`, because the intent stays `CLAIMED` during the call. For debits that is a duplicate-debit hazard. **This is M2's job, not M1's.**
- After M1: for links, a second execution of the same intent produces the same `reference_id` → provider rejects, lookup returns the existing link → no duplicate link. Debits are not executed at all until M2.

**Same intent retried:** link → idempotent via provider uniqueness + lookup. Debit (U8) → reuse persisted `order_id`; reconcile via `GET /orders/{id}/payments` before any new payment.

**Response lost:** link → `findPaymentLinkByReference`. Debit → `GET /orders/{order_id}/payments`; any `created/authorized/captured` payment ⇒ do not create another.

**Guarantees we can state:**
- At most one payment link per ActionIntent — *conditional on Razorpay enforcing `reference_id` uniqueness, to be confirmed by recording.*
- Nothing is recorded as recovered without `captured` evidence (or a `paid` link carrying a captured payment).
- **No exactly-once claim.** Two different intents for the same invoice can each create a link; preventing that is the M2 database constraint (one in-flight action per invoice).

**Overlap with Razorpay's own debit retries (DOC #5):** when debits are enabled, MEND must either send the order `notification` object (MEND owns retries) or not retry debits itself. Deferred to the M2 ADR; recorded here so it is not lost.

## 11. Contract-test strategy

All CI tests are deterministic and offline.

1. **Recorded fixtures (authoritative).** `scripts/record_razorpay_contracts.sh` calls Razorpay **test mode** (refuses `rzp_live_` keys) and writes `{request, response{status, body}, recorded_at, mode:"test"}` JSON to `backend/src/test/resources/razorpay/recorded/`, PII redacted. Replayed through `MockRestServiceServer`. A fixture's existence is what moves a §5 row from `NOT RECORDED` to `RECORDED`.
2. **Doc-derived fixtures (labelled).** Cases test mode cannot produce (5xx, UPI recurring) live in `.../razorpay/doc-derived/` with a `_source` URL field. Tests using them are suffixed `_docDerived`.
3. **Socket-level failure injection.** A JDK `com.sun.net.httpserver.HttpServer` that accepts then stalls / closes; plus a closed port for connection-refused. Proves `NotSent` vs `OutcomeUnknown` on real sockets rather than by mocking exceptions.

Per-operation assertions:
- Request: exact path, method, Basic auth header, `Content-Type`, **exact JSON key set** (no undocumented keys — the bug class that broke E1), integer paise amount.
- Response: every row of §9 has ≥ 1 test; no test may expect `Accepted` from a body without `id`.
- Evidence: every documented Razorpay status string maps to a `PaymentEvidenceStatus`; `authorized` is never recovery.

Legacy tests (rule 7 — documented behaviour change): assertions on `/v1/payments/{id}/retry` in `RazorpayPaymentProviderClientTest`, `Phase20_RazorpayTestModeE2EValidationTest`, `RazorpayTestModeEndToEndIntegrationTest` encode a non-existent API. They are **rewritten, not deleted**, to assert (a) `RETRY_PAYMENT` makes **zero** HTTP calls in Razorpay mode and returns `Rejected(NOT_SUPPORTED)`, and (b) the link flow works against recorded fixtures. Test count must not drop.

## 12. Actual test results

Numbers copied from the summary files (raw Maven logs are local-only; see owner decision D5).

| Run | Commit | Result | Source |
|---|---|---|---|
| Baseline (before M1) | `839f049` | `Tests run: 320, Failures: 0, Errors: 0, Skipped: 0` · BUILD SUCCESS · 58 classes | `docs/m1/baseline-test-run-summary.txt` |
| Pre-M1 health check (after 3 tenant-isolation fixes) | `5052719` | `Tests run: 323, Failures: 0, Errors: 0, Skipped: 0` · BUILD SUCCESS · 59 classes | `docs/health-check-after-summary.txt` |
| M1 final (U9, refreshed after re-recording) | `9948324` | `Tests run: 519, Failures: 0, Errors: 0, Skipped: 1` · BUILD SUCCESS · 78 classes | `docs/m1/m1-final-test-run-summary.txt` |

Skipped 1 = `PaymentReconciliationTest.originalPaymentFailureMustNotFailNonLinkIntent`, the `@Disabled("M2: correlation of original-payment failures")` test required by U7 (verified to fail when force-enabled).

**Test-mode recordings** (2026-10-05, `backend/src/test/resources/razorpay/recorded/`, 16 files; `01`–`14` re-recorded the same day with the rotated key — identical statuses and error texts, new ids): `01`–`06` payment links (create ok, duplicate reference 400, extra fields 400, missing amount 400, fetch by id, fetch by reference), `07`–`09` orders (two creates with the same receipt → two distinct orders; order payments), `10`–`12` the three legacy endpoints (404 "no Route matched with those values"), `13` GET unknown payment → 400 `BAD_REQUEST_ERROR` / `input_validation_failed`, `14` bad credentials → 401. `20` the link from `01` after a test-mode netbanking payment (`paid`, `amount_paid` 100, one `captured` payment), `21` that payment (`captured`, `order_id` present, `notes.mend_intent_id` copied from the link — verifies D1's premise). Not recorded: `22`–`24` (link/payment webhooks), `30`–`33` (recurring). Doc-derived fixtures (`doc-derived/`, each with `_source`): failed payment entity, 2xx without id, empty lookup, two links for one reference, paid link with a captured payment.

**Documentation re-check (U9, 2026-10-05):** create-standard payment link page re-opened; DOC #7 and #10 unchanged (integer `amount` ≥ 100 paise for INR; `reference_id` unique, ≤ 40 chars; "extra fields sent"; documented fields include `notify`, `reminder_enable`, `notes`, `expire_by`, `description`, `currency`; no idempotency header). Payments entity and fetch-all-links pages were fetched the same day during U4/U5. One text difference: the docs give the duplicate-reference error as "payment link creation with reference ID already attempted", while recording `02` returned "payment link with given reference_id: … already exists". Classification is unaffected because §9 never trusts the text; lookup-by-reference decides.
