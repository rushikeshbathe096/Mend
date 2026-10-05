# U2 — Razorpay transport + response classifier

## CONTEXT
See `AGENTS.md`, `M1-provider-correctness.md` §7 and **§9 (the classification table is the spec)**. U1 types exist.

## CURRENT IMPLEMENTATION
`RazorpayPaymentProviderClient` builds `RestTemplate` with `SimpleClientHttpRequestFactory` and maps exception classes coarsely. Read it; do not modify it.

## TASK
Build the HTTP layer and the classifier as separate, independently tested classes. No operations yet.

## FILES TO TOUCH (create)
- `com/mend/provider/razorpay/RazorpayProperties.java` (`@ConfigurationProperties("razorpay")`: baseUrl, keyId, keySecret, connectTimeoutMs, readTimeoutMs, singleTenantMerchantId) — reuse existing property names from `application.properties`; add `razorpay.single-tenant-merchant-id` (env `RAZORPAY_SINGLE_TENANT_MERCHANT_ID`, default empty).
- `.../razorpay/RazorpayCredentialsResolver.java` (interface: `Optional<RazorpayCredentials> resolve(MerchantRef)`)
- `.../razorpay/SingleTenantEnvCredentialsResolver.java` (returns creds only when merchant == configured single-tenant id AND key/secret non-blank; otherwise empty)
- `.../razorpay/RazorpayCredentials.java` (record; `toString()` masks secret)
- `.../razorpay/RazorpayHttp.java` — wraps Spring `RestClient` built on `JdkClientHttpRequestFactory` with connect/read timeouts. Methods: `RawResponse post(RazorpayCredentials, String path, Object body)`, `RawResponse get(RazorpayCredentials, String pathWithQuery)`. Returns `RawResponse` (sealed: `HttpReply(int status, String body, long latencyMs)` | `TransportFailure(TransportFailureKind kind, String cause, long latencyMs)`). Never throws for HTTP status codes (use `.retrieve()` with status handlers that don't throw, or `.exchange(...)`).
- `.../razorpay/TransportFailureKind.java`: `NOT_SENT`, `SENT_NO_RESPONSE`.
- `.../razorpay/dto/RazorpayErrorEnvelope.java` (record matching `{"error":{code,description,source,step,reason,metadata}}`)
- `.../razorpay/RazorpayResponseClassifier.java`: pure functions `classifyWrite(RawResponse)` → a `WriteClassification` (ACCEPTED_2XX with body | REJECTED(kind, code, reason, status) | NOT_SENT | UNKNOWN(cause, status)) and `classifyRead(RawResponse)` → FOUND_2XX | NOT_FOUND | UNAVAILABLE. Keep these classifier-level enums package-private; operations (U4/U5) convert them into U1 results.
- Tests under `src/test/java/com/mend/provider/razorpay/`.

## FILES NOT TO TOUCH
`client/*`, `service/*`, controllers, migrations, existing tests, `application-dev.properties` secrets.

## DESIGN
Exception → kind mapping (walk the cause chain):
- `java.net.ConnectException`, `java.net.UnknownHostException`, `java.net.http.HttpConnectTimeoutException` → `NOT_SENT`
- `java.net.http.HttpTimeoutException` (not connect), any other `IOException` → `SENT_NO_RESPONSE`
- anything else → `SENT_NO_RESPONSE` (conservative)
Status → write classification: exactly §9 (2xx→ACCEPTED_2XX; 400→REJECTED VALIDATION; 401→REJECTED AUTHENTICATION; 404→REJECTED NOT_SUPPORTED; 409→UNKNOWN; 429→REJECTED RATE_LIMITED; ≥500→UNKNOWN; other→UNKNOWN). Parse `RazorpayErrorEnvelope` when present; parse failures do not change the classification.
Auth header: `Basic base64(keyId:keySecret)`. Headers sent: `Authorization`, `Content-Type: application/json`, `Accept: application/json`. **No idempotency headers.**
JSON: use the application's Jackson 3 `JsonMapper` bean (Spring Boot 4 / `tools.jackson`), configured `NON_NULL` for request serialization and `FAIL_ON_UNKNOWN_PROPERTIES=false` for reads.
Logging: one INFO line per call: `event=provider_http method path status latency_ms outcome`. Never log Authorization or request bodies at INFO.

## INVARIANTS
- Transport never retries POST. GET retry is NOT in this unit (added in U5 if needed).
- Classifier is pure (no I/O, no clock) → trivially unit-testable.

## FAILURE SEMANTICS
As above; any unexpected exception inside `RazorpayHttp` becomes `TransportFailure(SENT_NO_RESPONSE)` for POST and is never rethrown.

## SECURITY REQUIREMENTS
Secret masked in `toString`, never logged. Resolver fails closed (empty) for any merchant other than the configured one, and when the configured id is blank.

## TEST REQUIREMENTS
- `RazorpayResponseClassifierTest`: one test per §9 status row incl. 2xx-with-empty-body (still ACCEPTED_2XX at this layer; the operation decides), malformed error JSON.
- `RazorpayHttpSocketTest` (real sockets, no mocks of exceptions):
  - closed port → `NOT_SENT`
  - JDK `HttpServer` that sleeps longer than read timeout → `SENT_NO_RESPONSE`
  - server that closes the connection after reading the request → `SENT_NO_RESPONSE`
  - server returning 503 → `HttpReply(503)`
  - assert Authorization header format and absence of `X-Razorpay-Idempotency-Header`/`X-Idempotency-Key`
- `SingleTenantEnvCredentialsResolverTest`: match/mismatch/blank id/blank secret.

## ACCEPTANCE CRITERIA
Suite green; no existing test changed; socket tests run < 10 s total (use ~300 ms read timeout in tests).

## DO NOT IMPLEMENT
Payment-link or payment operations, executor wiring, metrics registration, per-merchant DB credentials, WireMock dependency.

## REVIEW CHECKLIST
Does any path turn a read timeout into NOT_SENT? (must not). Is any exception swallowed without becoming a classified result? Is the secret anywhere in logs?
