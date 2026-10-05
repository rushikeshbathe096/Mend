# U8 — (CONDITIONAL) Recurring debit adapter operations, not wired

**Run only if U0 produced recurring fixtures (`30_*`–`33_*`). Otherwise skip and record "skipped: no test-mode recurring access" in ADR-001.**

## CONTEXT
DOC #3–#6 in `M1-provider-correctness.md` §4. Debit outcome is async; one debit in flight per token; `paid` order blocks further payments; receipt uniqueness per U0 result.

## TASK
Add adapter-level operations and contract tests. **Do not call them from any service.**

## FILES TO TOUCH
- `provider/PaymentProvider.java`: add `createDebitOrder(MerchantRef, CreateDebitOrder)` → `RequestResult<DebitOrder>`, `initiateRecurringDebit(MerchantRef, InitiateRecurringDebit)` → `RequestResult<DebitInitiated>`, `getOrderPayments(MerchantRef, String orderId)` → `LookupResult<List<PaymentEvidence>>`.
- New records in `com.mend.provider`: `CreateDebitOrder(key, MoneyMinor, tokenId, Instant paymentAfter /*nullable*/)`, `DebitOrder(orderId, key, amount, status)`, `InitiateRecurringDebit(orderId, customerId, tokenId, MoneyMinor, String email, String contact)`, `DebitInitiated(providerPaymentId)`.
- Razorpay DTOs + implementation; mock implementation; contract tests.

## DESIGN
- Order body: `amount, currency, receipt = key, payment_capture: true, notes{mend_intent_id}` (+ `notification{token_id, payment_after}` only if the M2 ADR decides MEND owns retries — **leave it out in M1** and note it).
- Recurring body: exactly the documented fields (`email, contact, amount, currency, order_id, customer_id, token, recurring:true, description, notes`).
- `initiateRecurringDebit` 2xx: require `razorpay_payment_id`; anything else → OutcomeUnknown. `Accepted` here means "debit queued", **never** captured.
- 400 on initiate → call `getOrderPayments` first: any payment in PENDING/AUTHORIZED/CAPTURED ⇒ `OutcomeUnknown("ORDER_HAS_PAYMENT")`, else `Rejected`.
- `toString` of `InitiateRecurringDebit` masks email/contact.

## TEST REQUIREMENTS
Replay fixtures; exact request key sets; 400 → lookup path; 5xx → OutcomeUnknown; DTO `toString` masking.

## ACCEPTANCE CRITERIA
Suite green; `grep -rn "initiateRecurringDebit" backend/src/main/java --include=*.java | grep -v provider/` → no matches (unwired).

## DO NOT IMPLEMENT
Executor wiring, token pre-checks, domain fields for token/customer, schema changes.
