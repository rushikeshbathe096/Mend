package com.mend.provider.razorpay;

import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RequestResult;
import com.mend.provider.razorpay.RazorpayResponseClassifier.ReadClassification;
import com.mend.provider.razorpay.dto.RazorpayErrorEnvelope;
import com.mend.provider.razorpay.dto.RazorpayPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
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
        throw new UnsupportedOperationException("createPaymentLink arrives in U4");
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key) {
        throw new UnsupportedOperationException("findPaymentLinkByReference arrives in U4");
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId) {
        throw new UnsupportedOperationException("getPaymentLink arrives in U4");
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
     * Razorpay answers a GET by id for a non-existent (well-formed) id with 400 BAD_REQUEST_ERROR
     * "The id provided does not exist" — recorded in fixture 13_fetch_payment_not_found.json.
     */
    private static boolean isUnknownIdOnGetById(ReadClassification.Unavailable unavailable) {
        RazorpayErrorEnvelope.Error error = unavailable.error();
        return unavailable.status() != null && unavailable.status() == 400
                && error != null && "BAD_REQUEST_ERROR".equals(error.code());
    }

    private LookupResult<PaymentEvidence> parsePayment(String body) {
        try {
            RazorpayPayment payment = responseMapper.readValue(body, RazorpayPayment.class);
            return new LookupResult.Found<>(mapper.toEvidence(payment, clock.instant()));
        } catch (MalformedProviderResponse | RuntimeException e) {
            log.warn("event=provider_malformed_response op=get_payment");
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
