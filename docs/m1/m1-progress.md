# M1 progress log

Orchestrated run started 2026-10-05 on branch `m1/u1-domain-port` (built on `pre-m1-health-check`, which is not merged to `main` yet).
Baseline floor: **320** tests (`docs/m1/baseline-test-run-summary.txt`; raw log local-only). Branch starting point: 323 (320 + 3 pre-M1 tenant-isolation regression tests).

Note: `docs/` is in `.gitignore`, so this file and other `docs/m1` edits are local only and cannot be committed without changing `.gitignore` (the owner's call).

## Fixture inventory (U0, recorded 2026-10-05, test mode)
Present in `backend/src/test/resources/razorpay/recorded/`: `01`–`14` (create link ok / duplicate ref / extra fields / missing amount, fetch link by id / by reference, create order ×2 same receipt, order payments, 3 legacy endpoints, payment not found, bad auth).
Absent: `20`/`21` (paid link + captured payment; the link was never paid), `22` (link-paid webhook), `30`–`33` (recurring) → **U8 will be skipped**.
Findings from the recordings:
- `02`: duplicate `reference_id` → 400 `BAD_REQUEST_ERROR` "payment link with given reference_id: … already exists…". Uniqueness is enforced (confirms §10 condition).
- `08`: same order `receipt` twice → two distinct orders. **`receipt` is NOT unique** (resolves the §5 AMBIGUOUS row; affects only U8/M2).
- `10`–`12`: invented endpoints → 404 `{"message":"no Route matched with those values"}` (route does not exist).
- `13`: GET unknown payment id → **400** `BAD_REQUEST_ERROR` "The id provided does not exist", reason `input_validation_failed` (U5 maps this exact case to NotFound).
- `14`: bad credentials → 401 `BAD_REQUEST_ERROR` "Authentication failed" (recorded on a GET).

---
## U1 — Domain port (commit `f0a2e75`)
Built: `com.mend.provider` port + sealed `RequestResult`/`LookupResult` + value objects (see U1 prompt). 26 new tests.
Tests: `Tests run: 349, Failures: 0, Errors: 0, Skipped: 0` (323 + 26).
Grep gates: `grep -ri razorpay backend/src/main/java/com/mend/provider/*.java` → 0. No double/float/BigDecimal, no Spring/JPA/HTTP imports.
Deviations: invalid construction always throws `IllegalArgumentException` (incl. null required fields in result records); `PaymentLinkSnapshot.referenceId` nullable (links not created by MEND); `isAccepted()`/`isFound()` helpers.
Review (fresh subagent, AGENTS.md + prompt + diff only): 0 blockers. Findings and fixes (commit `M1 U1: review fixes`):
- MAJOR DUPLICATE_REFERENCE could be read as "terminal" → Javadoc: only produced after lookup (per U4/§9). Fixed (doc).
- MINOR referenceId typed ProviderAttemptKey can throw on untrusted input → kept type (§8 spec); Javadoc requires adapters to treat an invalid reference as malformed. **U4 must implement this** (→ OutcomeUnknown/Unavailable, never throw).
- MINOR List.copyOf NPE on null element → IAE + test. Fixed.
- MINOR negative amounts accepted → rejected in PaymentEvidence/PaymentLinkSnapshot + tests. Not done: paid>amount or currency checks (would make untrusted responses unrepresentable; mapper decides).
- MINOR description in toString → toString shows length only; blank rejected; tests. Fixed.
- NIT cause free text → Javadoc "short machine code". Fixed (doc). **U2/U4/U5 must pass codes, not exception messages.**
- NIT isAccepted/isFound → no change (allowed).
Tests after fixes: `Tests run: 356, Failures: 0, Errors: 0, Skipped: 0`.

### Adaptations for U2 (written before applying)
1. `RazorpayProperties` binds existing `razorpay.api.base-url` as nested `api.baseUrl` with a `baseUrl()` accessor. Reason: prompt says reuse existing property names, and the existing name is nested.
2. New `provider/razorpay/RazorpayConfiguration.java` (`@Configuration`, `@ConditionalOnProperty(mend.payment.provider=razorpay)`, `@EnableConfigurationProperties`). Reason: a record `@ConfigurationProperties` needs registration; this keeps changes out of shared `config/*`.
3. JSON: use the application's Jackson 3 `tools.jackson.databind.ObjectMapper` bean (`RedisConfig`), deriving NON_NULL-write and lenient-read copies via `rebuild()`. Reason: there is no separate `JsonMapper` bean in this app.
4. `Content-Type: application/json` sent on POST only (GET has no body); `Accept` on both.
5. Classifier carries the parsed `RazorpayErrorEnvelope.Error` (nullable) through Rejected/Unknown/NotFound/Unavailable. Reason: U5 must recognise fixture 13 (400 `BAD_REQUEST_ERROR` on GET-by-id) as NotFound.
6. `RazorpayHttp` takes a `RestClient.Builder` in a second constructor so U4/U5 can bind `MockRestServiceServer`; the Spring constructor builds the JDK request factory with the configured timeouts.


## U2 — Transport + classifier (commit `41b1ff7`)
Built: `provider/razorpay/` `RazorpayProperties` (+`RazorpayConfiguration`), `RazorpayCredentials` (masked toString), `RazorpayCredentialsResolver` + `SingleTenantEnvCredentialsResolver` (fail-closed), `RawResponse` (HttpReply | TransportFailure), `TransportFailureKind`, `RazorpayHttp` (RestClient on JDK HttpClient, Basic auth, no idempotency headers, never throws/retries; exception chain → NOT_SENT only for connect-timeout/refused/unknown-host), `RazorpayResponseClassifier` (§9 table, package-private sealed results), `dto/RazorpayErrorEnvelope`. Added `razorpay.single-tenant-merchant-id` to `application.properties`.
Tests: 34 new (classifier 16, socket 5, failure-kind mapping 6, resolver 5, properties binding 2). Socket tests ≈0.5 s.
Full suite: `Tests run: 390, Failures: 0, Errors: 0, Skipped: 0`. No existing test changed.
Deviations: adaptations 1–6 above; extra `RazorpayHttpFailureKindTest` covers connect-timeout/DNS (not reproducible on real sockets) and asserts a read timeout is never NOT_SENT. Socket tests also assert the POST was received exactly once (no client retry).
Review (fresh subagent): 0 blockers. Fixed in `9a0f832 M1 U2: review fixes`:
- MAJOR `RazorpayProperties` record toString printed key secret → masked + test.
- MINOR `.uri(String)` = URI template (double-encoding, `{}` expansion, path injection risk) → absolute `java.net.URI`, verbatim; Javadoc: callers validate ids + URL-encode query values. Test proves `%2B` arrives unchanged. **U4/U5 must validate ids and URL-encode query values.**
- MINOR unbounded body read → 1 MiB cap → `SENT_NO_RESPONSE/BODY_TOO_LARGE` (never truncated) + test.
- MINOR JDK `jdk.httpclient.enableAllMethodRetry` could resend POST → constructor refuses to start; Javadoc notes JDK's own single GET resend on stale pooled connections.
- MINOR query string in INFO log (PII risk) → path only.
- MINOR socket test timing → latch.
- NIT test constructor public → package-private.
Not changed: pre-send build failures stay `SENT_NO_RESPONSE` (prompt's explicit conservative rule); HttpClient not closed (singleton for app lifetime); `com.mend.provider.razorpay` Spring imports are by design (§7 puts Spring in the adapter subpackage).
Tests after fixes: 393 (390 + 3).

### Adaptations for U3 (written before applying)
1. Test class `WebhookAmountResolverTest` is a plain unit test with a mocked `WebhookEventRepository` (Mockito) and a real Jackson 3 `ObjectMapper`, so it needs no DB.
2. `resolveOriginalAmount` uses the same entity-node lookup order as `extractAmountFromWebhook` (`payload.payment.entity` → `payment.entity` → root), via one shared private method, so both paths read the same node.

### Adaptations for U5 (written before applying; U3 is implemented and waits for U2 review before commit)
1. Response DTOs are read with a mapper that disables `ACCEPT_FLOAT_AS_INT` (built once by `RazorpayResponseClassifier.responseMapper`). Reason: Jackson's default would silently turn `100.5` into `100` — a fabricated amount. A non-integral amount → `MalformedProviderResponse` → `Unavailable("MALFORMED")`.
2. GET retry (max 2, backoff `min(2000, 200·2^n)` ms full jitter) only for transport failures, 429 and 5xx — not for 400/401/other client errors, where a retry cannot change the answer. The prompt says "may retry on Unavailable"; this narrows it.
3. `getPayment` with no credentials for the merchant → `Unavailable("NO_CREDENTIALS_FOR_MERCHANT")`, zero HTTP calls (reads have no Rejected).
4. Doc-derived fixture `doc-derived/payment_failed_entity.json` copies the failed-payment example from the payments entity page verbatim except `email`/`contact` → `<redacted>` (same policy as recorded fixtures).
5. Fixture `21` (captured) does not exist → no captured contract replay; `captured` is covered by mapper tests only. Not a hard stop: U5 says "if present".
6. Unrecognised status values are truncated/sanitised before logging (untrusted input in logs).
7. Doc facts used (fetched 2026-10-05 from the §4 URLs): link `notes` may be `[]` (array) and `customer` may be `[]`, so link DTOs must not type them as objects; link `payments[]` items carry `payment_id`, `plink_id`, `amount`, `method`, `status`.

## U3 — Amount in minor units (commit `9ddc161`)
Built: `WebhookAmountResolver.resolveOriginalAmount(merchantId, paymentId)` → `Optional<MoneyMinor>`: integral (`isIntegralNumber && canConvertToLong`), > 0 amount and textual currency from the same entity node; empty + DEBUG reason code on anything else (no payload logging). Shared private `paymentEntity(...)`; `resolveAmount`/`extractAmountFromWebhook` logic unchanged.
Tests: `WebhookAmountResolverTest` 9 (integral ok, decimal, string, zero, missing currency, USD, malformed JSON, other merchant, existing `resolveAmount` regression).
Full suite (U2 fixes + U3 in tree): `Tests run: 402, Failures: 0, Errors: 0, Skipped: 0`.
Deviation: non-INR currency → empty (MoneyMinor is INR-only in M1), tested.
Review (fresh subagent): 0 blockers/majors. Fixed in `797f08b M1 U3: review fixes`: tenant test now `verify(scoped lookup)` + `verifyNoMoreInteractions`; resolveAmount regression asserts for decimal/malformed/blank; explicit `CURRENCY_UNSUPPORTED` reason; tests for -100, >long, blank and numeric currency.
**Rejected** finding "require entity id == paymentId": `CampaignLifecycleService.processClassificationResult` sets `campaign.paymentId = event.getExternalEventId()` (`CampaignLifecycleService.java:156`), and `WebhookService.extractExternalEventId` makes that `"<eventType>_<entityId>"` (e.g. `payment.failed_pay_x`) for real Razorpay webhooks (no top-level id). The entity id never equals `campaign.paymentId` in production, so the check would break every real flow.
Tests after fixes: `Tests run: 405, Failures: 0, Errors: 0, Skipped: 0`.
**Out-of-scope finding (M2):** `campaign.paymentId` for real webhooks is a composite `"payment.failed_pay_x"`, not a Razorpay payment id. Anything that needs the real `pay_` id (e.g. `getPayment`, payment-link description) must not assume `campaign.paymentId` is one.

### Adaptation for U6 from U3
The dispatcher's link description must not embed `campaign.paymentId` raw if it could be a composite; use the campaign id instead (no PII either way). Decided at U6.


## U5 — Evidence reads + mapper (commit `cfb8b4e`)
Built: `RazorpayEvidenceMapper` (payment + link status, `toEvidence` → `MalformedProviderResponse`), `dto/RazorpayPayment` (no customer fields), `Sleeper`, `RazorpayPaymentProvider` with `getPayment` (link methods `UnsupportedOperationException` until U4), `RazorpayResponseClassifier.responseMapper` (ignore unknown props, no float→int coercion). Doc-derived fixture `doc-derived/payment_failed_entity.json` (`_source` = payments entity page).
Fixture 13 decision: recording shows 400 `BAD_REQUEST_ERROR` for a well-formed unknown id → mapped to NotFound for GET-by-id only, with a comment citing the fixture.
Tests: 39 new (mapper 29 incl. every row/branch, contract 10: fixture 13, doc-derived failed, 404, 503×3 retry with backoff bounds, transport retry recovers, 401 not retried, malformed 2xx, fractional amount, invalid ids zero HTTP, unknown merchant zero HTTP).
Full suite: `Tests run: 444, Failures: 0, Errors: 0, Skipped: 0`.
Deviations: adaptations 1–7 above. Branch coverage verified by inspection (no coverage tool in the build; adding JaCoCo would be a new dependency).
Review (fresh subagent): 0 blockers. Fixed in `6dfcaf6 M1 U5: review fixes`:
- MAJOR fixture-13 check (400 + BAD_REQUEST_ERROR) too broad — BAD_REQUEST_ERROR is generic → now also requires `reason == input_validation_failed` (machine field as recorded; description text not matched, per §9). Test: other 400 → Unavailable.
- MINOR `"amount":"100"` still coerced → `MapperFeature.ALLOW_COERCION_OF_SCALARS` disabled; test.
- MINOR `catch RuntimeException` → only `JacksonException`/`MalformedProviderResponse`, reason code logged (no message).
- MINOR missing tests → 429 retry, interrupted backoff keeps flag, backoff cap, WARN log asserted (OutputCaptureExtension), more injection ids.
- NIT `Thread.sleep(Duration)`.
Not changed: `_source` uses the mintlify host — §4 names it as the docs index used for verification. Stub link methods: replaced in U4 (next).
Tests after fixes: `Tests run: 450, Failures: 0, Errors: 0, Skipped: 0`.


### Adaptations for U4 (written before applying)
1. 401 contract test replays recorded fixture `14_bad_auth` (recorded on a GET) as the response to the create POST; same envelope, labelled in the test.
2. Doc-derived fixtures (in `doc-derived/`, each with `_source` + `_note` stating how it was derived): `create_link_2xx_missing_id` (recorded `01` body with `id` removed; `_source` create-standard page, which documents `id` as part of the response), `fetch_links_two_results` (two link objects shaped like the fetch-all example, distinct ids, same reference). The 503 case uses an empty body (only the status matters) — no invented body.
3. Reference that is not a valid `ProviderAttemptKey` in a response → treated as malformed (create → `OutcomeUnknown("MALFORMED_2XX")`; reads → `Unavailable("MALFORMED")`). From U1 review.
4. `getPaymentLink` validates id `^plink_[A-Za-z0-9]{1,40}$` before URL use (→ NotFound, zero HTTP), mirroring U5 and the U2 review requirement. The reference query value is URL-encoded.
5. All link GETs use U5's bounded retry policy (§9: GETs may retry, max 2). POST is never retried.
6. Create response also requires non-null `currency` and `amount_paid` (snapshot fields); missing → `OutcomeUnknown("MALFORMED_2XX")`.
7. `Rejected.providerCode` = error.code, `providerReason` = error.description (logged only; U6 audits codes, not text).
8. Local precondition codes: `AMOUNT_BELOW_MINIMUM` (< 100 paise), `CURRENCY_NOT_SUPPORTED`; credentials: `NO_CREDENTIALS_FOR_MERCHANT`.
10. Additional doc-derived fixtures: `fetch_links_empty` (`{"payment_links":[]}`, the documented list shape with zero items; no recorded no-match lookup exists) and `fetch_link_paid_with_captured_payment` (a `paid` link whose `payments[]` item uses the documented item fields `payment_id, plink_id, amount, method, status, created_at, updated_at`; needed because fixture `20` does not exist).
9. `expire_by` sent as epoch seconds (Razorpay timestamps are UNIX seconds, as in recorded `01` `created_at`).
11. Request DTO component is `linkNotify` with `@JsonProperty("notify")` (Java forbids a record component named `notify`); wire name unchanged.
12. From U5 review: the fixture-13 NotFound rule is payment-specific; `getPaymentLink` uses plain 404 → NotFound (no recording of a 400 for unknown link ids), other 400 → Unavailable.

## U4 — Payment-link operations (commit `769a2af`)
Built: `createPaymentLink`, `findPaymentLinkByReference`, `getPaymentLink` in `RazorpayPaymentProvider`; DTOs `RazorpayCreatePaymentLinkRequest` (+`Notes`), `RazorpayLinkNotify`, `RazorpayPaymentLink` (+`Payment`), `RazorpayPaymentLinkList`. Doc-derived fixtures: `create_link_2xx_missing_id`, `fetch_links_empty`, `fetch_links_two_results`, `fetch_link_paid_with_captured_payment`.
Tests: `RazorpayPaymentLinkContractTest` 31 (recorded 01–06, 12, 14 + doc-derived; every §9 create row; exact request key set; zero-HTTP local checks).
Full suite: `Tests run: 481, Failures: 0, Errors: 0, Skipped: 0`.
Deviations: adaptations 1–12 above.
Review (fresh subagent): 0 blockers. Fixed in `M1 U4: review fixes`:
- MAJOR currency not compared on 2xx/dedup → compared (mismatch → OutcomeUnknown / DUPLICATE_REFERENCE) + tests.
- MAJOR provider-returned link id / captured payment ids not shape-checked → malformed if not `plink_`/`pay_`-shaped + tests.
- MINOR dedup not signalled in structured line → `dedup=true` + log-capture test.
- MINOR no explicit read-timeout test → SocketTimeoutException → OutcomeUnknown, 1 POST.
- NIT fixture `_note` now discloses reference_id substitution; misleading test comment fixed.
Not changed: invalid *input* id → NotFound without HTTP (U5 spec states this explicitly for payments; links mirror it; no executor path uses getPaymentLink); `CURRENCY_NOT_SUPPORTED` branch untestable because `MoneyMinor` is INR-only (kept as defensive guard); local codes in `providerCode` (spec-sanctioned); unused DTO fields `orderId`, `Payment.amount` (harmless).
Out-of-scope (M2): dedup-mismatch leaves a live link at Razorpay under our reference; dedup returns Accepted(existing) even if that link is expired/cancelled — reconciliation must check `state`.
Tests after fixes: `Tests run: 487, Failures: 0, Errors: 0, Skipped: 0`.

### Adaptations for U6 (written before applying)
A. **Executor report type kept.** `executeActionIntent` keeps returning `dto/payment/PaymentExecutionResult` (+ `PaymentExecutionStatus`), now only as the executor's *report*, not a provider result. Reason: `ActionScheduler` (M2-owned, not in FILES TO TOUCH) exposes `List<PaymentExecutionResult>`, and `StartRecoveryResponseDto.executionResult` is the frontend's JSON contract (`StartRecoveryModal` shows the green "recovered" banner only for `status === 'SUCCESS'`). U6 says delete `dto/payment/*` "only after all references are gone"; they are not gone without touching M2 code and the frontend. Added `PaymentExecutionStatus.PENDING` + `PaymentExecutionResult.pending(...)` for LinkIssued/Unknown (a report DTO value, not an intent/campaign state). Deleted: `PaymentProviderClient`, `RazorpayPaymentProviderClient`, `MockPaymentProviderClient`, `PaymentExecutionRequest`. `ActionScheduler` and `CampaignController` unchanged.
B. **Synthetic debit seam (mock only).** `com.mend.provider.ProviderCapabilities` (`supportsRecurringDebit()`); `provider/mock/SyntheticDebitSimulator` (mock-only interface); `MockPaymentProvider implements PaymentProvider, ProviderCapabilities, SyntheticDebitSimulator` (true); `provider/razorpay/RazorpayProviderCapabilities` (false, razorpay mode). Dispatcher: RETRY_PAYMENT (and MANUAL_REVIEW, to keep its existing mock behaviour) → synthetic only if capabilities say so AND a simulator bean exists; otherwise `NotSupported` with zero provider calls. Razorpay mode has neither, so RECOVERED cannot come from a Razorpay request outcome.
C. `ProviderActionOutcome` adds `SyntheticCaptured(reference)` for the design-doc "(mock) synthetic captured success" row.
D. **Mock knobs preserved:** `setSimulatedStatus(PaymentExecutionStatus)` (SUCCESS→accept, FAILURE→`Rejected(VALIDATION,"MOCK_DECLINED",reason)`, ERROR→`OutcomeUnknown`), `setSimulatedFailureReason`, `setSimulatedErrorMessage`, `setSimulatedException`, `reset`, `getInvocationCount`, `getRecordedRequests`, `getLastRequest`; new `setSimulatedNotSent(boolean)`. Recorded calls are `MockProviderCall(operation, merchantId, campaignId, intentId, attemptKey)`; tests that read `getLastRequest().getIdempotencyKey()` change to the attempt key (M1 behaviour change: the provider key is `ProviderAttemptKey.forIntent(intentId)`).
E. An exception thrown by the provider/simulator → `Unknown("UNEXPECTED_PROVIDER_EXCEPTION")` (the call may have been sent; invariant 4).
F. Unknown → intent EXECUTING, campaign unchanged, attempt `UNKNOWN`, report `PENDING`. Existing tests asserting ERROR/timeout → FAILED change with `// M1 behaviour change` comments (ActionExecutionServiceTest.testProviderErrorExecution, Phase10 tests 4/5, ActionSchedulerTransactionIntegrationTest provider-exception test).
G. Rejected/NotSent/NotSupported → `campaign_attempts.failure_reason` = provider reason if present, else the code (keeps existing "Card expired" assertions). Audit rows carry ids/codes only (kind, code, http status, attempt key, provider ref), never provider text.
H. Link description: `"Payment for campaign <campaignId>"` (no PII; `campaign.paymentId` can be the composite `payment.failed_pay_x`, see U3).
I. Razorpay-mode executor tests use a JDK `HttpServer` stub (`RazorpayStubServer`, test support) whose URL is injected with `@DynamicPropertySource`. Fixture `01` is replayed with `reference_id` echoed from the request (the recorded reference belongs to the recording run; documented in the test). Phase20 `testConfigurationAudit` then asserts the *default* base URL from `RazorpayProperties` instead of the overridden property.
J. Legacy test classes are converted in place (same file/class names, same test counts): `RazorpayPaymentProviderClientTest` (7), `MockPaymentProviderClientTest` (5), `PaymentProviderClientIntegrationTest` (1), `RazorpayProviderSelectionTest` (2), Phase20 (6), RazorpayTestModeE2E (3).
K. `AuditEventType` gains the four PROVIDER_* names (documentation; `AuditService` takes strings).

## U6 — Wire executor (commit `fcfd481`) — production behaviour change
Built: `service/ProviderActionDispatcher`, `service/ProviderActionOutcome` (LinkIssued, Rejected, NotSent, Unknown, NotSupported, SyntheticCaptured), `provider/ProviderCapabilities`, `provider/razorpay/RazorpayProviderCapabilities` (false), `provider/mock/MockPaymentProvider` (+ `SyntheticDebitSimulator`, `MockProviderCall`), `PaymentExecutionStatus.PENDING` + `PaymentExecutionResult.pending`, four PROVIDER_* `AuditEventType` names. `ActionExecutionService` dispatches through the dispatcher and maps outcomes per the design-doc table; `prepareExecution` verified byte-identical (diff of the method against the pre-U6 file). Deleted `PaymentProviderClient`, `RazorpayPaymentProviderClient`, `MockPaymentProviderClient`, `PaymentExecutionRequest`. Test support: `RazorpayStubServer` (JDK HttpServer).
Tests: new `ActionExecutionServiceProviderOutcomeTest` (8: LinkIssued, Rejected, AMOUNT_UNRESOLVED zero calls, NotSent, Unknown + findExpiredClaims ignores EXECUTING, exception → Unknown, unsupported type → NotSupported zero calls, synthetic capture labelled SYNTHETIC). Legacy tests rewritten in place with `// M1 behaviour change` comments, counts unchanged: RazorpayPaymentProviderClientTest 7, MockPaymentProviderClientTest 5, PaymentProviderClientIntegrationTest 1, RazorpayProviderSelectionTest 2, Phase20 6 (RETRY razorpay mode → NotSupported, zero HTTP, audit reason RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2; REQUEST_CUSTOMER_ACTION vs fixture 01 → intent EXECUTING, campaign EXECUTING not RECOVERED; fixture 03 + empty lookup → FAILED/EXHAUSTED), RazorpayTestModeE2E 3, ActionExecutionServiceTest 10, Phase10 11, ActionSchedulerTransactionIntegrationTest 10, Phase19_5 10.
Changed assertions (all with comments): timeout/5xx/exception → EXECUTING + UNKNOWN + PENDING (was FAILED + ERROR); provider key assertions now the attempt key; synthetic prefix `pay_mock_` (was `mock_ref_`); unsupported action type report FAILURE (was ERROR); Phase20 config audit checks the default base URL (the property points at the stub); Phase20 analytics recovered 0 (was 1, nothing captured).
Full suite: `Tests run: 495, Failures: 0, Errors: 0, Skipped: 0` (487 + 8).
Grep gates: `backend/src/main` has 0 matches for the U6 gate pattern; 0 `PaymentProviderClient` references. **Deviation:** the literal gate over `backend/src` still matches (a) test assertions that the forbidden headers are *absent* and comments citing fixture 10, (b) recorded fixtures `10`–`12`, which U0 records on purpose as evidence that those endpoints do not exist. Making the literal gate pass would require deleting recorded evidence or obscuring the header names in absence checks; not done. **Acceptance criterion "grep gate clean" is therefore NOT MET as written** (met for `backend/src/main`).
Review (fresh subagent): 0 blockers/majors. Fixed in `45638a7 M1 U6: review fixes`:
- MINOR pre-call exceptions (amount resolution, simulator wiring) became Unknown and left intents EXECUTING with zero calls → simulator resolved once at startup; pre-call failure → `NotSent("PRE_CALL_EXCEPTION")`; only the provider call maps exceptions to Unknown. New `ProviderActionDispatcherTest` (3).
- MINOR Phase20 dropped the recovery-rate assertion → now asserts `0.0`.
- NIT per-assertion `// M1 behaviour change` comments in MockPaymentProviderClientTest; original failure-reason string restored.
- NIT mock `PENDING` knob meant success → now simulates an unknown outcome.
- NIT DEBUG stack traces added (ERROR lines stay ids-only).
Not changed, with reasons:
- `failure_reason` holds provider reason text: column is `TEXT` (V1), not an audit row; audit rows carry codes only.
- PENDING downstream: `ActionScheduler` does not branch on status; `CampaignController` maps non-success/non-failure to the neutral "Recovery orchestration initiated" message; frontend shows the green banner only for `SUCCESS`. Safe.
- `SyntheticDebitSimulator` stays in `provider.mock` (mock-only by design). Link description uses the campaign id (merchant-visible in the console, no PII).
- **Deviation recorded:** MANUAL_REVIEW in razorpay mode is now `NotSupported` → FAILED→EXHAUSTED + `PROVIDER_REQUEST_REJECTED` (was ERROR → FAILED + `ACTION_ERROR`). Follows the spec's NotSupported row; mock mode unchanged.
Out-of-scope (M2): AUTHENTICATION / AMOUNT_UNRESOLVED / NotSent permanently exhaust the campaign although they are MEND-side/config errors; `ACTION_FAILED`/`ACTION_ERROR` audit rows are no longer written by the executor (dashboards counting them will drift).
Tests after fixes (U7 also in tree): `Tests run: 505, Failures: 0, Errors: 0, Skipped: 1`.

### Adaptations for U7 (written before applying)
1. Reconciliation instantiates the pure `RazorpayEvidenceMapper` (no Spring bean needed); all status interpretation goes through it. The event type no longer decides success; a payload with no status is a no-op (previously `payment.captured` with no status counted as success).
2. Link events: success ⇔ `payload.payment_link.entity.status` maps to PAID **and** `payload.payment.entity.status` maps to CAPTURED. Failure ⇔ payment status FAILED. Everything else → `unsupported` + INFO.
3. Correlation additions: `notes.mend_intent_id` read from the payment entity and from the payment-link entity (U4 puts notes on the link); legacy `notes.action_intent_id` kept. Strategy 3 also matches the payment-link id against `intent.responseReference` (U6 stores the plink id there). `receipt` is no longer used as an idempotency-key fallback.
4. Fixture `22_webhook_link_paid.json` does not exist, so the link-paid test uses a payload built to the U7 rule (link PAID + payment CAPTURED), labelled in the test as not a recording. The `payment_link.paid` webhook name/shape stays AMBIGUOUS (§5).
5. The U7 prompt requires one `@Disabled("M2: correlation of original-payment failures")` test documenting the strategy-4 gap. It is a new test (no existing test is disabled), but it will show as `Skipped: 1` in the totals.

## U7 — Reconciliation on captured evidence (commit `1fff235`) — production behaviour change
Built: `DefaultPaymentReconciliationService` routes status interpretation through `RazorpayEvidenceMapper` (`evidencedOutcome`): success only on CAPTURED (link events: link PAID + payment CAPTURED), failure only on FAILED, everything else `unsupported` + INFO. Correlation: `notes.mend_intent_id` (payment + link entities, preferred) with legacy `action_intent_id`; strategy 3b matches the link id to `responseReference`; `receipt` fallback removed. Late-webhook and tenant checks untouched.
Tests: 7 new in `PaymentReconciliationTest` (authorized not recovered; `active` no-op; captured via mend_intent_id recovers; EXECUTING intent + paid link with captured payment recovers; paid link + authorized payment no-op; receipt no longer correlates; `@Disabled("M2: correlation of original-payment failures")` — verified failing when force-enabled: `expected: <EXECUTING> but was: <FAILED>`). Existing A–J unchanged.
Full suite: `Tests run: 505, Failures: 0, Errors: 0, Skipped: 1` (Skipped = the spec-mandated M2 test).
Deviations: adaptations 1–5 above; no fixture 22, so the link-paid test is a labelled hand-built payload.
Review (fresh subagent): 0 blockers. Fixed in `M1 U7: review fixes`:
- MINOR `asText()` on notes values let `null`/non-text junk win over the legacy key → only non-blank strings accepted.
- MINOR missing coverage → link-notes correlation, precedence + legacy fallback (incl. `mend_intent_id: null`), captured-without-status no-op, refunded/created/late_authorized/unknown no-op (parameterised), failed via mend_intent_id.
- NIT WARN on conflicting link/payment intent ids; `@Disabled` imported.
Not changed: duplicate `responseReference` → `findByResponseReference` exception risk. Since U6 each intent gets its own link id (attempt key → unique `reference_id`), and mock ids derive from the key, so duplicates cannot arise today; a multi-row query would also touch the repository outside U7's files. Noted for M2.
Tests after fixes: `Tests run: 513, Failures: 0, Errors: 0, Skipped: 1`.

### HARD STOP before U9 — design question (U7 review finding 1, MAJOR)
U7's spec rule "failure ⇔ payment status FAILED" is applied to intents that issued a **payment link**. A link accepts several payment attempts; one failed attempt does not end the link. If Razorpay copies the link's `notes.mend_intent_id` onto each attempt's payment entity (not verified — no recording of a link payment exists), then a `payment.failed` for one attempt marks the intent FAILED and the campaign EXHAUSTED; a later `payment.captured` / `payment_link.paid` for the same link is then ignored by late-webhook protection, so **captured money would not be recognised** (no false recovery, but real recovery missed).
Fixing it changes U7's result semantics ("Failure ⇔ FAILED"), which the run rules forbid changing unilaterally. Options for the owner:
 (a) Keep the rule; first record a paid-after-failed link in test mode (U0 step 5) to see whether payment entities carry the link notes. If they don't, the failure stays unmatched (strategy 4 can't match a new `pay_` id) and the issue does not occur.
 (b) Amend U7: for intents whose `responseReference` is a `plink_` id, a FAILED payment is a no-op (+INFO); only link terminal events (expired/cancelled — M2) end the intent.
 (c) Defer to M2 together with the strategy-4 gap (same area: correlating failures to the right attempt).
Recommendation: (a) now (one test-mode payment answers it), then (b) if notes are copied.
U9 (close-out, ADR-001 → Accepted) was **not started**: accepting the ADR with this open would be premature.


---
## Owner decisions (2026-10-05, recorded before acting)
- **D1** Option (b) approved as an amendment to U7, without new recordings: for an intent whose `responseReference` is a `plink_` id, a FAILED payment webhook is a no-op (`ReconciliationResult.unsupported` + INFO `link_attempt_failed`); the intent stays EXECUTING. Recovery still requires link PAID + payment CAPTURED, or payment CAPTURED correlated to the intent. Non-link intents keep "failure ⇔ FAILED". Link expiry/cancel is M2 (not implemented). Tests use hand-built payloads labelled "not a recording": failed-then-captured on the same link → RECOVERED; failed attempt alone → intent EXECUTING, campaign not EXHAUSTED.
- **D2** U6 grep gate redefined as `backend/src/main` only — accepted deviation.
- **D3** Accepted: `PaymentExecutionResult` kept as the executor report type; MANUAL_REVIEW → NotSupported in razorpay mode.
- **D4** U8 skipped (no recurring fixtures). Fixtures 20–24 (paid link, captured payment, link webhooks) not recorded: the `payment_link.paid` webhook row stays AMBIGUOUS in §5; "record paid-link + webhook fixtures" is an open evidence item in ADR-001.
- **D5** Track `docs/`: remove the `docs/` entry from `.gitignore`, commit `docs/` separately ("Track docs/: M1 design, ADR, progress log") before the U9 commits.

- **D5 (amended by owner, supersedes D5 above):** do NOT commit raw Maven logs. `.gitignore`: replace `docs/` with `docs/**/*.txt` and `!docs/**/*-summary.txt`. Each raw log gets a `<name>-summary.txt` (command, date, commit, final `Tests run` line, BUILD result, per-class surefire totals; < ~10 KB). Commit all `docs/**/*.md` + `*-summary.txt`; raw logs stay local. Docs reference the summary (noting the raw log is local-only). Verify with `git add -n docs/ | wc -l` and `du -sh` before committing (target well under 1 MB).

## U7 amendment D1 (commits `964332d`, D1 review fixes)
Built: in `DefaultPaymentReconciliationService`, a FAILED payment correlated to an intent whose `responseReference` starts with `plink_` returns `unsupported` + INFO `event=link_attempt_failed` (ids only); the check sits after tenant, late-webhook and idempotency guards. Non-link intents unchanged.
Tests: `failedLinkAttemptAloneIsNoOp`, `failedThenCapturedOnSameLinkRecovers` (hand-built, "Not a recording"). The former `@Disabled` link-intent M2 test now passes under D1 and is enabled (assertions unchanged); the remaining non-link strategy-4 gap moved to a new `@Disabled("M2: correlation of original-payment failures")` test, verified failing when force-enabled (`expected: <EXECUTING> but was: <FAILED>`).
Review (fresh subagent): 0 blockers/majors. Fixed: D1 check moved after late-webhook/idempotency guards (U7: late-webhook behaviour unchanged); non-link gap test also asserts campaign not EXHAUSTED; distinct payment ids in D1 tests. Tenant checks confirmed earlier in the method (before correlation-dependent logic).
Full suite: `Tests run: 516, Failures: 0, Errors: 0, Skipped: 1`.

## U9 — Close-out
### Adaptations for U9 (written before applying)
1. Gate regressions found by the U9 grep pass, fixed as a small code commit before the close-out docs:
   - U1 gate: `ProviderCapabilities` Javadoc (added in U6) contained the word "Razorpay" in `com.mend.provider` → reworded (comment only).
   - Plan checklist #3 ("no razorpay imports outside `com.mend.provider.razorpay` + config"): U7's spec made `DefaultPaymentReconciliationService` use `RazorpayEvidenceMapper` directly. Fix: domain interface `com.mend.provider.PaymentStatusInterpreter` (payment + link status mapping), implemented by `RazorpayEvidenceMapper`, provided as a bean from `com.mend.config.ProviderEvidenceConfiguration`; reconciliation depends on the interface. Behaviour unchanged; the mapper stays the single interpreter.
2. §4–§10 of `M1-provider-correctness.md` are not edited (run rule); recording evidence and the doc re-check go into §12 and ADR-001 "Evidence". §5's `payment_link.paid` row stays AMBIGUOUS (D4).
### U9 results
- Step 1: final run `Tests run: 516, Failures: 0, Errors: 0, Skipped: 1`, BUILD SUCCESS, 78 classes, on commit `b5077c9` (summary `docs/m1/m1-final-test-run-summary.txt`; raw log local-only). ≥ 320 baseline.
- Step 2: §12 filled from the summary files (baseline 320 @ `839f049`, health check 323 @ `5052719`, final 516 @ `b5077c9`) plus recording inventory.
- Step 3: create-link doc page re-checked 2026-10-05 — unchanged; documented duplicate-reference text differs from recording `02` (classification unaffected). §4/§5 not edited.
- Step 4: plan checklist rows 1–13 and the four extra gates ticked with SHAs.
- Step 5: ADR-001 → Accepted; Evidence lists fixtures, test results, U8 skipped, owner decisions D1–D5 and open evidence items; "M2 backlog from M1" section added.
- Step 6: grep gates — U1 0 (after the `b5077c9` Javadoc fix), U6 `backend/src/main` 0 (D2), `PaymentProviderClient` in main 0, U8 0 (skipped), plan #3 clean (after `b5077c9`). `CampaignStatus.RECOVERED` is set at runtime only by reconciliation on captured evidence and the mock synthetic path (other hits: dev seed data, analytics count).
- Step 7: `docs/m1/M1-summary.md` (10 lines + M2 backlog).
- U9 code-fix review (fresh subagent): 0 blockers. Findings 1–3 (stale commit in §12, docs mixed into the commit, untracked summary) came from reviewing `git diff HEAD~1` against the working tree; `b5077c9` contains only `backend/src`, §12 already cites `b5077c9`, and the summary is committed with the U9 docs. Finding 4 checked: the config bean is the only `PaymentStatusInterpreter` bean (`RazorpayPaymentProvider` keeps a private instance); razorpay-mode contexts boot in Phase20 and the selection test. Finding 6: `PaymentLinkState.UNRECOGNIZED` exists. Not changed: NIT field name `evidenceMapper`.
- AGENTS.md still references `docs/m1/baseline-test-run.txt` (now local-only); AGENTS.md is not editable under the run rules — the tracked equivalent is `baseline-test-run-summary.txt`.
