# U5 — Payment evidence reads + RazorpayEvidenceMapper

## CONTEXT
Invariant 6.3. DOC #9: payment statuses `created, authorized, captured, refunded, failed`; only `captured` is money. Link statuses `created, partially_paid, expired, cancelled, paid`.

## TASK
Implement `getPayment` and a single mapper that every Razorpay-status interpretation in MEND will use (U7 reuses it).

## FILES TO TOUCH (create)
- `provider/razorpay/RazorpayEvidenceMapper.java` — pure: `PaymentEvidenceStatus mapPaymentStatus(String)`, `PaymentLinkState mapLinkStatus(String)`, `PaymentEvidence toEvidence(RazorpayPayment, Instant observedAt)`.
- `provider/razorpay/dto/RazorpayPayment.java`
- `RazorpayPaymentProvider.getPayment` (if the class exists from U4; else create the class with this method and stubs that throw `UnsupportedOperationException` for link methods)
- Tests: `RazorpayEvidenceMapperTest`, `RazorpayGetPaymentContractTest`

## FILES NOT TO TOUCH
Reconciliation (U7), executor, old clients.

## DESIGN
Mapping (case-insensitive, trimmed): `captured→CAPTURED`, `authorized→AUTHORIZED_NOT_CAPTURED`, `created→PENDING`, `failed→FAILED`, `refunded→REFUNDED`, null/other (incl. `late_authorized` until recorded) → `UNRECOGNIZED` + WARN `event=unrecognized_provider_status value=…`.
Link: `paid→PAID`, `partially_paid→PARTIALLY_PAID`, `created→CREATED`, `expired→EXPIRED`, `cancelled→CANCELLED`, other→`UNRECOGNIZED`.
`toEvidence`: require id + integral amount + currency; otherwise throw `MalformedProviderResponse` (checked → converted by the operation into `Unavailable("MALFORMED")`).
`getPayment`: GET `/v1/payments/{id}`; path id must match `^pay_[A-Za-z0-9]{1,40}$` else `NotFound` without HTTP (prevents path injection); 404→NotFound. **Check fixture `13_fetch_payment_not_found.json` first:** Razorpay's docs show a 400 "is not a valid id" for unknown ids on some endpoints. If the recording shows 400 for a non-existent id, map *that exact recorded case* (400 + `BAD_REQUEST_ERROR` on GET-by-id) to NotFound and cite the fixture in a comment; otherwise 400 → Unavailable. 2xx→Found(evidence); transport/5xx→Unavailable. GET may retry up to 2 times on Unavailable with backoff `min(2000, 200·2^n) ms` + full jitter (inject a `Sleeper` for tests).

## INVARIANTS
Single source of status interpretation. `UNRECOGNIZED` is never recovery.

## FAILURE SEMANTICS
Reads never produce `Accepted/Rejected`; only Found/NotFound/Unavailable.

## SECURITY REQUIREMENTS
Validate ids before putting them in a URL path. Do not log `email`/`contact` from payment bodies.

## TEST REQUIREMENTS
Every mapping row; `authorized` not recovery; recorded fixtures `13` (not found), `21` (captured, from U0 step 5) if present; doc-derived failed-payment fixture from the entity page; retry count verified with a fake sleeper; invalid id → zero HTTP calls.

## ACCEPTANCE CRITERIA
Suite green; mapper 100 % branch-covered by tests.

## DO NOT IMPLEMENT
Order/debit reads (U8), reconciliation changes (U7), caching.
