# U4 — Payment-link operations in RazorpayPaymentProvider

## CONTEXT
`AGENTS.md`; `M1-provider-correctness.md` §4 (DOC #7, #8), §8, §9, §10. U0 fixtures exist in `backend/src/test/resources/razorpay/recorded/`. U1, U2, U5 merged.

## CURRENT IMPLEMENTATION
`RazorpayPaymentProviderClient` POSTs `/v1/payment_links` with `{receipt, idempotency_key, payment_id}` — wrong body. Do not modify it in this unit.

## TASK
Implement `createPaymentLink`, `findPaymentLinkByReference`, `getPaymentLink` in a new `RazorpayPaymentProvider implements PaymentProvider` (`getPayment` arrives from U5 — if U5 is merged, include it; otherwise leave it throwing `UnsupportedOperationException` and add it in U5).

## FILES TO TOUCH (create)
- `provider/razorpay/RazorpayPaymentProvider.java` (`@Component`, `@ConditionalOnProperty(name="mend.payment.provider", havingValue="razorpay")`)
- `provider/razorpay/dto/RazorpayCreatePaymentLinkRequest.java`, `RazorpayPaymentLink.java`, `RazorpayPaymentLinkList.java`, `RazorpayLinkNotify.java`
- Tests: `RazorpayPaymentLinkContractTest.java` (replays recorded fixtures via `MockRestServiceServer.bindTo(RestClient.Builder)`)

## FILES NOT TO TOUCH
`client/*` (both old clients stay until U6), executor, reconciliation, migrations.

## DESIGN
createPaymentLink(merchant, cmd):
1. Resolve credentials → empty ⇒ `Rejected(AUTHENTICATION, "NO_CREDENTIALS_FOR_MERCHANT")`, no HTTP.
2. Local checks: amount ≥ 100 paise, currency INR ⇒ else `Rejected(LOCAL_PRECONDITION)`, no HTTP.
3. Body = exactly: `amount, currency, reference_id = cmd.key, description, expire_by (if set), notify {sms:false, email:false}, reminder_enable:false, notes {mend_intent_id, mend_merchant_id}`. Nothing else.
4. POST `/v1/payment_links`.
5. Classification:
   - ACCEPTED_2XX: parse `RazorpayPaymentLink`; require non-blank `id`, `reference_id == key`, `amount == requested`. Any mismatch/missing ⇒ `OutcomeUnknown("MALFORMED_2XX")`. Else `Accepted(snapshot)`.
   - REJECTED(VALIDATION) on 400 ⇒ call `findPaymentLinkByReference` first: Found ⇒ `Accepted(existing)` and log `dedup=true` (verify amount matches; if not ⇒ `Rejected(DUPLICATE_REFERENCE)`); NotFound ⇒ `Rejected(VALIDATION, code, reason)`; Unavailable ⇒ `OutcomeUnknown`.
   - Other REJECTED ⇒ `Rejected(kind…)`. NOT_SENT ⇒ `NotSent`. UNKNOWN ⇒ `OutcomeUnknown`.
findPaymentLinkByReference: GET `/v1/payment_links?reference_id={urlencoded}`; 0 items ⇒ NotFound; 1 ⇒ Found; >1 ⇒ `Unavailable("MULTIPLE_LINKS_FOR_REFERENCE")` + ERROR log (should be impossible per docs).
getPaymentLink: GET `/v1/payment_links/{id}`; 404 ⇒ NotFound.
Snapshot mapping: link status string → `PaymentLinkState` via `RazorpayEvidenceMapper` (U5); `capturedPaymentIds` from `payments[]` where status == captured.

## INVARIANTS
`Accepted` never implies money. No field outside the documented list is ever sent (assert exact key set in tests). No fabricated ids.

## FAILURE SEMANTICS
§9 rows for create link, exactly.

## SECURITY REQUIREMENTS
No customer object, no PII in body or logs. Notes contain only UUIDs.

## TEST REQUIREMENTS
Using recorded fixtures (`01`, `02`, `03`, `04`, `05`, `06`, `14`):
- happy path → Accepted, state CREATED, amountPaid 0
- duplicate reference → lookup Found → Accepted(existing) with dedup
- extra-field/validation 400 → lookup NotFound → Rejected(VALIDATION)
- 401 fixture → Rejected(AUTHENTICATION)
- request JSON key set equals the documented set exactly
Doc-derived: 2xx missing `id` → OutcomeUnknown; 503 → OutcomeUnknown; lookup returning 2 links → Unavailable.
Local: 99 paise → Rejected(LOCAL_PRECONDITION) with zero HTTP calls (`mockServer.verify()` with no expectations); unknown merchant → Rejected(AUTHENTICATION) with zero calls.

## ACCEPTANCE CRITERIA
Suite green; contract tests reference fixture filenames; each §9 create-link row has a test.

## DO NOT IMPLEMENT
Executor wiring, cancellation, notifications, UPI links, partial payments, debit operations.

## REVIEW CHECKLIST
Is there any path where a 400 becomes Rejected **without** a lookup? Does the mapper treat unknown link status as anything but UNRECOGNIZED?
