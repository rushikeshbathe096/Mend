package com.mend.provider.razorpay;

import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.MoneyMinor;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import com.mend.provider.razorpay.RazorpayResponseClassifier.ReadClassification;
import com.mend.provider.razorpay.RazorpayResponseClassifier.WriteClassification;
import com.mend.provider.razorpay.dto.RazorpayCreatePaymentLinkRequest;
import com.mend.provider.razorpay.dto.RazorpayErrorEnvelope;
import com.mend.provider.razorpay.dto.RazorpayLinkNotify;
import com.mend.provider.razorpay.dto.RazorpayPayment;
import com.mend.provider.razorpay.dto.RazorpayPaymentLink;
import com.mend.provider.razorpay.dto.RazorpayPaymentLinkList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Razorpay implementation of the provider port. Only the endpoints in M1-provider-correctness.md §6. */
@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "razorpay")
public class RazorpayPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    private static final Pattern PAYMENT_ID = Pattern.compile("^pay_[A-Za-z0-9]{1,40}$");
    private static final Pattern LINK_ID = Pattern.compile("^plink_[A-Za-z0-9]{1,40}$");
    /** DOC #7: INR payment links need amount >= 100 paise. */
    static final long MIN_LINK_AMOUNT_MINOR = 100;
    static final int MAX_GET_RETRIES = 2;

    private final RazorpayHttp http;
    private final RazorpayCredentialsResolver credentialsResolver;
    private final RazorpayResponseClassifier classifier;
    private final RazorpayEvidenceMapper mapper;
    private final ObjectMapper responseMapper;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public RazorpayPaymentProvider(RazorpayHttp http, RazorpayCredentialsResolver credentialsResolver, ObjectMapper objectMapper) {
        this(http, credentialsResolver, objectMapper, Clock.systemUTC(), Sleeper.SYSTEM);
    }

    RazorpayPaymentProvider(RazorpayHttp http, RazorpayCredentialsResolver credentialsResolver, ObjectMapper objectMapper,
                            Clock clock, Sleeper sleeper) {
        this.http = http;
        this.credentialsResolver = credentialsResolver;
        this.classifier = new RazorpayResponseClassifier(objectMapper);
        this.mapper = new RazorpayEvidenceMapper();
        this.responseMapper = RazorpayResponseClassifier.responseMapper(objectMapper);
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command) {
        Optional<RazorpayCredentials> credentials = credentialsResolver.resolve(merchant);
        if (credentials.isEmpty()) {
            return logged(merchant, command, new RequestResult.Rejected<>(RejectionKind.AUTHENTICATION, "NO_CREDENTIALS_FOR_MERCHANT", null, null), 0, false);
        }
        MoneyMinor amount = command.amount();
        // Defensive: MoneyMinor is INR-only in M1, so this cannot trigger today.
        if (!"INR".equals(amount.currency())) {
            return logged(merchant, command, new RequestResult.Rejected<>(RejectionKind.LOCAL_PRECONDITION, "CURRENCY_NOT_SUPPORTED", null, null), 0, false);
        }
        if (amount.amountMinor() < MIN_LINK_AMOUNT_MINOR) {
            return logged(merchant, command, new RequestResult.Rejected<>(RejectionKind.LOCAL_PRECONDITION, "AMOUNT_BELOW_MINIMUM", null, null), 0, false);
        }

        RawResponse response = http.post(credentials.get(), "/v1/payment_links", createRequest(merchant, command));
        WriteClassification classification = classifier.classifyWrite(response);
        RequestResult<PaymentLinkSnapshot> result = switch (classification) {
            case WriteClassification.Accepted2xx accepted -> acceptCreated(accepted, command);
            case WriteClassification.Rejected rejected when rejected.kind() == RejectionKind.VALIDATION ->
                    resolveRejectedCreate(credentials.get(), command, rejected);
            case WriteClassification.Rejected rejected -> new RequestResult.Rejected<>(rejected.kind(),
                    code(rejected.error()), description(rejected.error()), rejected.status());
            case WriteClassification.NotSent notSent -> new RequestResult.NotSent<>(notSent.cause());
            case WriteClassification.Unknown unknown -> new RequestResult.OutcomeUnknown<>(unknown.cause(), unknown.status());
        };
        boolean dedup = classification instanceof WriteClassification.Rejected && result instanceof RequestResult.Accepted;
        return logged(merchant, command, result, response.latencyMs(), dedup);
    }

    private static RazorpayCreatePaymentLinkRequest createRequest(MerchantRef merchant, CreatePaymentLink command) {
        return new RazorpayCreatePaymentLinkRequest(
                command.amount().amountMinor(),
                command.amount().currency(),
                command.key().value(),
                command.description(),
                command.expireBy() == null ? null : command.expireBy().getEpochSecond(),
                new RazorpayLinkNotify(false, false),
                false,
                new RazorpayCreatePaymentLinkRequest.Notes(command.intentId().toString(), merchant.merchantId().toString()));
    }

    /** A 2xx is only Accepted when the body proves it is our link: id present, same reference, amount and currency. */
    private RequestResult<PaymentLinkSnapshot> acceptCreated(WriteClassification.Accepted2xx accepted, CreatePaymentLink command) {
        try {
            PaymentLinkSnapshot snapshot = toSnapshot(responseMapper.readValue(accepted.body(), RazorpayPaymentLink.class));
            if (!command.key().equals(snapshot.referenceId()) || !sameMoney(snapshot, command)) {
                log.warn("event=provider_malformed_response op=create_payment_link reason=MISMATCH");
                return new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", accepted.status());
            }
            return new RequestResult.Accepted<>(snapshot);
        } catch (MalformedProviderResponse e) {
            log.warn("event=provider_malformed_response op=create_payment_link reason={}", e.getMessage());
            return new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", accepted.status());
        } catch (JacksonException e) {
            log.warn("event=provider_malformed_response op=create_payment_link reason={}", e.getClass().getSimpleName());
            return new RequestResult.OutcomeUnknown<>("MALFORMED_2XX", accepted.status());
        }
    }

    /**
     * §9: a 400 on create may mean "a link with this reference already exists" (recorded fixture 02). The 400 text is
     * never trusted; the lookup by reference decides.
     */
    private RequestResult<PaymentLinkSnapshot> resolveRejectedCreate(RazorpayCredentials credentials, CreatePaymentLink command,
                                                                     WriteClassification.Rejected rejected) {
        return switch (lookupByReference(credentials, command.key())) {
            case LookupResult.Found<PaymentLinkSnapshot> found -> {
                if (!sameMoney(found.value(), command)) {
                    yield new RequestResult.Rejected<>(RejectionKind.DUPLICATE_REFERENCE, code(rejected.error()),
                            description(rejected.error()), rejected.status());
                }
                log.info("event=provider_dedup op=create_payment_link attempt_key={} provider_link_id={} dedup=true",
                        command.key().value(), found.value().providerLinkId());
                yield new RequestResult.Accepted<>(found.value());
            }
            case LookupResult.NotFound<PaymentLinkSnapshot> notFound -> new RequestResult.Rejected<>(RejectionKind.VALIDATION,
                    code(rejected.error()), description(rejected.error()), rejected.status());
            case LookupResult.Unavailable<PaymentLinkSnapshot> unavailable ->
                    new RequestResult.OutcomeUnknown<>("LOOKUP_UNAVAILABLE_AFTER_400", rejected.status());
        };
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key) {
        Optional<RazorpayCredentials> credentials = credentialsResolver.resolve(merchant);
        if (credentials.isEmpty()) {
            return new LookupResult.Unavailable<>("NO_CREDENTIALS_FOR_MERCHANT", null);
        }
        return lookupByReference(credentials.get(), key);
    }

    private LookupResult<PaymentLinkSnapshot> lookupByReference(RazorpayCredentials credentials, ProviderAttemptKey key) {
        String path = "/v1/payment_links?reference_id=" + URLEncoder.encode(key.value(), StandardCharsets.UTF_8);
        return switch (readWithRetry(() -> http.get(credentials, path))) {
            case ReadClassification.Found2xx found -> parseLinkList(found.body(), key);
            // The list endpoint answers "no match" with an empty list; a 404 means something else is wrong.
            case ReadClassification.NotFound notFound -> new LookupResult.Unavailable<>("HTTP_404", notFound.status());
            case ReadClassification.Unavailable unavailable -> new LookupResult.Unavailable<>(unavailable.cause(), unavailable.status());
        };
    }

    private LookupResult<PaymentLinkSnapshot> parseLinkList(String body, ProviderAttemptKey key) {
        try {
            RazorpayPaymentLinkList list = responseMapper.readValue(body, RazorpayPaymentLinkList.class);
            if (list == null || list.paymentLinks() == null) {
                throw new MalformedProviderResponse("MISSING_PAYMENT_LINKS");
            }
            if (list.paymentLinks().isEmpty()) {
                return new LookupResult.NotFound<>();
            }
            if (list.paymentLinks().size() > 1) {
                log.error("event=provider_multiple_links_for_reference attempt_key={} count={}", key.value(), list.paymentLinks().size());
                return new LookupResult.Unavailable<>("MULTIPLE_LINKS_FOR_REFERENCE", null);
            }
            PaymentLinkSnapshot snapshot = toSnapshot(list.paymentLinks().get(0));
            if (!key.equals(snapshot.referenceId())) {
                throw new MalformedProviderResponse("REFERENCE_MISMATCH");
            }
            return new LookupResult.Found<>(snapshot);
        } catch (MalformedProviderResponse e) {
            log.warn("event=provider_malformed_response op=find_payment_link_by_reference reason={}", e.getMessage());
            return new LookupResult.Unavailable<>("MALFORMED", null);
        } catch (JacksonException e) {
            log.warn("event=provider_malformed_response op=find_payment_link_by_reference reason={}", e.getClass().getSimpleName());
            return new LookupResult.Unavailable<>("MALFORMED", null);
        }
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId) {
        if (providerLinkId == null || !LINK_ID.matcher(providerLinkId).matches()) {
            // Never put an unvalidated id into a URL path.
            return new LookupResult.NotFound<>();
        }
        Optional<RazorpayCredentials> credentials = credentialsResolver.resolve(merchant);
        if (credentials.isEmpty()) {
            return new LookupResult.Unavailable<>("NO_CREDENTIALS_FOR_MERCHANT", null);
        }
        return switch (readWithRetry(() -> http.get(credentials.get(), "/v1/payment_links/" + providerLinkId))) {
            case ReadClassification.Found2xx found -> parseLink(found.body(), providerLinkId);
            case ReadClassification.NotFound notFound -> new LookupResult.NotFound<>();
            case ReadClassification.Unavailable unavailable -> new LookupResult.Unavailable<>(unavailable.cause(), unavailable.status());
        };
    }

    private LookupResult<PaymentLinkSnapshot> parseLink(String body, String providerLinkId) {
        try {
            PaymentLinkSnapshot snapshot = toSnapshot(responseMapper.readValue(body, RazorpayPaymentLink.class));
            if (!providerLinkId.equals(snapshot.providerLinkId())) {
                throw new MalformedProviderResponse("ID_MISMATCH");
            }
            return new LookupResult.Found<>(snapshot);
        } catch (MalformedProviderResponse e) {
            log.warn("event=provider_malformed_response op=get_payment_link reason={}", e.getMessage());
            return new LookupResult.Unavailable<>("MALFORMED", null);
        } catch (JacksonException e) {
            log.warn("event=provider_malformed_response op=get_payment_link reason={}", e.getClass().getSimpleName());
            return new LookupResult.Unavailable<>("MALFORMED", null);
        }
    }

    /** Link entity → snapshot. Missing required fields or a reference that is not ours in shape → malformed. */
    private PaymentLinkSnapshot toSnapshot(RazorpayPaymentLink link) throws MalformedProviderResponse {
        if (link == null || link.id() == null || link.id().isBlank()) {
            throw new MalformedProviderResponse("MISSING_ID");
        }
        if (!LINK_ID.matcher(link.id()).matches()) {
            throw new MalformedProviderResponse("INVALID_ID");
        }
        if (link.amount() == null || link.amount() < 0 || link.amountPaid() == null || link.amountPaid() < 0) {
            throw new MalformedProviderResponse("MISSING_AMOUNT");
        }
        if (link.currency() == null || link.currency().isBlank()) {
            throw new MalformedProviderResponse("MISSING_CURRENCY");
        }
        ProviderAttemptKey reference = null;
        if (link.referenceId() != null && !link.referenceId().isEmpty()) {
            try {
                reference = new ProviderAttemptKey(link.referenceId());
            } catch (IllegalArgumentException e) {
                throw new MalformedProviderResponse("INVALID_REFERENCE");
            }
        }
        List<String> capturedPaymentIds = link.payments() == null ? List.of() : link.payments().stream()
                .filter(p -> p != null && mapper.mapPaymentStatus(p.status()) == PaymentEvidenceStatus.CAPTURED)
                .map(RazorpayPaymentLink.Payment::paymentId)
                .toList();
        // Provider ids end up in URL paths later: a captured payment id that is not pay_-shaped is malformed.
        if (capturedPaymentIds.stream().anyMatch(id -> id == null || !PAYMENT_ID.matcher(id).matches())) {
            throw new MalformedProviderResponse("INVALID_PAYMENT_ID");
        }
        return new PaymentLinkSnapshot(link.id(), reference, mapper.mapLinkStatus(link.status()), link.amount(),
                link.amountPaid(), link.currency(), link.shortUrl(), capturedPaymentIds);
    }

    private static boolean sameMoney(PaymentLinkSnapshot snapshot, CreatePaymentLink command) {
        return snapshot.amountMinor() == command.amount().amountMinor() && command.amount().currency().equals(snapshot.currency());
    }

    private static String code(RazorpayErrorEnvelope.Error error) {
        return error == null ? null : error.code();
    }

    private static String description(RazorpayErrorEnvelope.Error error) {
        return error == null ? null : error.description();
    }

    /** One structured line per create call (design doc "Metrics"). Ids and codes only. */
    private static RequestResult<PaymentLinkSnapshot> logged(MerchantRef merchant, CreatePaymentLink command,
                                                             RequestResult<PaymentLinkSnapshot> result, long latencyMs, boolean dedup) {
        String outcome = switch (result) {
            case RequestResult.Accepted<PaymentLinkSnapshot> a -> "result=Accepted provider_link_id=" + a.value().providerLinkId()
                    + " dedup=" + dedup;
            case RequestResult.Rejected<PaymentLinkSnapshot> r -> "result=Rejected kind=" + r.kind() + " code=" + r.providerCode()
                    + " http_status=" + r.httpStatus();
            case RequestResult.NotSent<PaymentLinkSnapshot> n -> "result=NotSent cause=" + n.cause();
            case RequestResult.OutcomeUnknown<PaymentLinkSnapshot> u -> "result=OutcomeUnknown cause=" + u.cause()
                    + " http_status=" + u.httpStatus();
        };
        log.info("event=provider_call op=create_payment_link {} latency_ms={} merchant_id={} intent_id={} attempt_key={}",
                outcome, latencyMs, merchant.merchantId(), command.intentId(), command.key().value());
        return result;
    }

    @Override
    public LookupResult<PaymentEvidence> getPayment(MerchantRef merchant, String providerPaymentId) {
        if (providerPaymentId == null || !PAYMENT_ID.matcher(providerPaymentId).matches()) {
            // Never put an unvalidated id into a URL path.
            return new LookupResult.NotFound<>();
        }
        Optional<RazorpayCredentials> credentials = credentialsResolver.resolve(merchant);
        if (credentials.isEmpty()) {
            return new LookupResult.Unavailable<>("NO_CREDENTIALS_FOR_MERCHANT", null);
        }
        ReadClassification classification = readWithRetry(() -> http.get(credentials.get(), "/v1/payments/" + providerPaymentId));
        return switch (classification) {
            case ReadClassification.Found2xx found -> parsePayment(found.body());
            case ReadClassification.NotFound notFound -> new LookupResult.NotFound<>();
            case ReadClassification.Unavailable unavailable -> isUnknownIdOnGetById(unavailable)
                    ? new LookupResult.NotFound<>()
                    : new LookupResult.Unavailable<>(unavailable.cause(), unavailable.status());
        };
    }

    /**
     * Razorpay answers a GET by id for a non-existent (well-formed) id with 400, code BAD_REQUEST_ERROR,
     * reason input_validation_failed — recorded in fixture 13_fetch_payment_not_found.json. BAD_REQUEST_ERROR alone
     * is generic, so both machine fields must match; any other 400 stays Unavailable (fails safe).
     */
    private static boolean isUnknownIdOnGetById(ReadClassification.Unavailable unavailable) {
        RazorpayErrorEnvelope.Error error = unavailable.error();
        return unavailable.status() != null && unavailable.status() == 400
                && error != null && "BAD_REQUEST_ERROR".equals(error.code())
                && "input_validation_failed".equals(error.reason());
    }

    private LookupResult<PaymentEvidence> parsePayment(String body) {
        try {
            RazorpayPayment payment = responseMapper.readValue(body, RazorpayPayment.class);
            return new LookupResult.Found<>(mapper.toEvidence(payment, clock.instant()));
        } catch (MalformedProviderResponse e) {
            log.warn("event=provider_malformed_response op=get_payment reason={}", e.getMessage());
            return new LookupResult.Unavailable<>("MALFORMED", null);
        } catch (JacksonException e) {
            // Exception message may echo body text; log the type only.
            log.warn("event=provider_malformed_response op=get_payment reason={}", e.getClass().getSimpleName());
            return new LookupResult.Unavailable<>("MALFORMED", null);
        }
    }

    /** GETs are safe to repeat: up to {@link #MAX_GET_RETRIES} retries on transport failure, 429 or 5xx. */
    private ReadClassification readWithRetry(Supplier<RawResponse> call) {
        ReadClassification classification = classifier.classifyRead(call.get());
        for (int retry = 0; retry < MAX_GET_RETRIES && isRetryable(classification); retry++) {
            try {
                sleeper.sleep(backoff(retry));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return classification;
            }
            classification = classifier.classifyRead(call.get());
        }
        return classification;
    }

    private static boolean isRetryable(ReadClassification classification) {
        if (!(classification instanceof ReadClassification.Unavailable unavailable)) {
            return false;
        }
        Integer status = unavailable.status();
        return status == null || status == 429 || status >= 500;
    }

    /** Full jitter over {@code min(2000, 200 * 2^n)} ms. */
    static Duration backoff(int retry) {
        long cap = Math.min(2000L, 200L << retry);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(cap + 1));
    }
}
