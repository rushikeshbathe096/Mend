# U1 — Domain provider port, result types, value objects

## CONTEXT
MEND (Java 21, Spring Boot 4) recovers failed Razorpay payments. Read `AGENTS.md` and `docs/m1/M1-provider-correctness.md` §7–§8 first. M1 replaces a provider abstraction that collapses all outcomes into SUCCESS/FAILURE/ERROR.

## CURRENT IMPLEMENTATION
`com.mend.client.PaymentProviderClient` (`executeAction`), `com.mend.dto.payment.PaymentExecutionRequest/Result/Status`. Leave them alone in this unit.

## TASK
Create the provider-agnostic domain contract only. No Razorpay code, no wiring.

## FILES TO TOUCH (create)
- `backend/src/main/java/com/mend/provider/PaymentProvider.java`
- `.../provider/RequestResult.java` (sealed: Accepted, Rejected, NotSent, OutcomeUnknown)
- `.../provider/RejectionKind.java`
- `.../provider/LookupResult.java` (sealed: Found, NotFound, Unavailable)
- `.../provider/PaymentEvidence.java`, `PaymentEvidenceStatus.java`
- `.../provider/PaymentLinkSnapshot.java`, `PaymentLinkState.java`
- `.../provider/CreatePaymentLink.java` (command: `ProviderAttemptKey key, MoneyMinor amount, String description, Instant expireBy (nullable), UUID intentId`)
- `.../provider/MoneyMinor.java`, `ProviderAttemptKey.java`, `MerchantRef.java` (record wrapping UUID)
- Tests under `backend/src/test/java/com/mend/provider/`

## FILES NOT TO TOUCH
Everything else. Especially `client/*`, `service/*`, migrations, existing tests.

## DESIGN / INTERFACES
Exactly as in `M1-provider-correctness.md` §7–§8. `PaymentProvider` has four methods: `createPaymentLink`, `findPaymentLinkByReference`, `getPaymentLink`, `getPayment`. Each takes `MerchantRef` first.
- `MoneyMinor(long amountMinor, String currency)`: compact constructor rejects `amountMinor <= 0`, null currency, currency other than `"INR"` (M1 scope). No `double`, no `BigDecimal`.
- `ProviderAttemptKey.forIntent(UUID)` → `"mnd_" + uuid.toString().replace("-", "")` (lowercase, 36 chars). Constructor validates `^[a-z0-9_]{1,40}$`.
- `PaymentEvidenceStatus.isRecovery()` returns true only for `CAPTURED`.
- `RequestResult` / `LookupResult`: records with null-checks; `Rejected` requires non-null kind.
- Results expose helpers only if trivially derived (e.g. `isAccepted()`); prefer pattern matching `switch` at call sites.

## INVARIANTS
- Money is `long` minor units.
- Nothing in `com.mend.provider` references Razorpay, HTTP, Spring, or JPA.
- Records are immutable; lists copied with `List.copyOf`.

## FAILURE SEMANTICS
Value objects throw `IllegalArgumentException` on invalid construction. No other behaviour in this unit.

## SECURITY REQUIREMENTS
`toString()` of `CreatePaymentLink` must not include any customer data (it has none in M1 — keep it that way).

## TEST REQUIREMENTS
- `MoneyMinorTest`: 0, negative, non-INR, null → reject; 100 → ok.
- `ProviderAttemptKeyTest`: deterministic for same UUID; ≤ 40 chars; distinct UUIDs → distinct keys; rejects `:`/uppercase/41 chars.
- `PaymentEvidenceStatusTest`: only `CAPTURED.isRecovery()`.
- Exhaustive `switch` over `RequestResult` compiles without default (proves sealed hierarchy).

## ACCEPTANCE CRITERIA
- `./mvnw test` green; test count = baseline + new tests.
- `grep -ri razorpay backend/src/main/java/com/mend/provider/*.java` → no matches.

## DO NOT IMPLEMENT
Razorpay adapter, HTTP, Spring beans, executor changes, debit operations, metrics.

## REVIEW CHECKLIST (for you after the agent finishes)
No `double`/`float`/`BigDecimal`; no Spring annotations in `com.mend.provider`; sealed + records used; no "util" classes.
