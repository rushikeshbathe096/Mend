package com.mend.provider.mock;

import com.mend.domain.enums.ActionType;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.ProviderCapabilities;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deterministic dev/test provider. Every result is SYNTHETIC: ids are prefixed {@code plink_mock_} / {@code pay_mock_}
 * and every log line carries the {@code SYNTHETIC} marker. The simulation knobs apply to every state-changing call.
 */
@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentProvider implements PaymentProvider, ProviderCapabilities, SyntheticDebitSimulator {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);

    private volatile PaymentExecutionStatus simulatedStatus = PaymentExecutionStatus.SUCCESS;
    private volatile String simulatedFailureReason = "Simulated payment failure";
    private volatile String simulatedErrorMessage = "Simulated gateway connection timeout";
    private volatile RuntimeException simulatedException;
    private volatile boolean simulatedNotSent;
    private final ConcurrentLinkedQueue<MockProviderCall> recordedRequests = new ConcurrentLinkedQueue<>();
    private final Map<ProviderAttemptKey, PaymentLinkSnapshot> linksByReference = new ConcurrentHashMap<>();
    /** Synthetic payments made against synthetic links (test knob {@link #payLink}); never invented otherwise. */
    private final Map<String, PaymentEvidence> paymentsById = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong paymentSequence = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean simulatedLostResponse;
    private volatile boolean simulatedLookupUnavailable;

    @Override
    public boolean supportsRecurringDebit() {
        return true;
    }

    /** Links: the mock enforces reference uniqueness like Razorpay. The synthetic debit has no idempotency, so it is never resend-safe. */
    @Override
    public boolean supportsIdempotentResend(ActionType actionType) {
        return actionType == ActionType.REQUEST_CUSTOMER_ACTION;
    }

    @Override
    public RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command) {
        record("create_payment_link", merchant, null, command.intentId(), command.key());
        RequestResult<PaymentLinkSnapshot> simulated = simulatedFailure();
        if (simulated != null) {
            return simulated;
        }
        String id = "plink_mock_" + command.key().value().replace("mnd_", "");
        // reference_id is unique, as recorded in fixture 02: a second create with the same key creates nothing, and the
        // port answers with the existing link (what M1's lookup-on-400 path returns for Razorpay).
        boolean duplicate = linksByReference.containsKey(command.key());
        PaymentLinkSnapshot snapshot = linksByReference.computeIfAbsent(command.key(), key -> new PaymentLinkSnapshot(id, key,
                PaymentLinkState.CREATED, command.amount().amountMinor(), 0, command.amount().currency(),
                "https://mock.invalid/" + id, List.of()));
        log.info("SYNTHETIC event=mock_provider op=create_payment_link provider_link_id={} attempt_key={} duplicate_reference={}",
                snapshot.providerLinkId(), command.key().value(), duplicate);
        if (simulatedLostResponse) {
            log.info("SYNTHETIC event=mock_provider simulated_lost_response=true");
            return new RequestResult.OutcomeUnknown<>("MOCK_RESPONSE_LOST", null);
        }
        return new RequestResult.Accepted<>(snapshot);
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key) {
        if (simulatedLookupUnavailable) {
            return new LookupResult.Unavailable<>("MOCK_LOOKUP_UNAVAILABLE", null);
        }
        PaymentLinkSnapshot snapshot = linksByReference.get(key);
        return snapshot == null ? new LookupResult.NotFound<>() : new LookupResult.Found<>(snapshot);
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId) {
        if (simulatedLookupUnavailable) {
            return new LookupResult.Unavailable<>("MOCK_LOOKUP_UNAVAILABLE", null);
        }
        return linksByReference.values().stream()
                .filter(link -> link.providerLinkId().equals(providerLinkId))
                .findFirst()
                .<LookupResult<PaymentLinkSnapshot>>map(LookupResult.Found::new)
                .orElseGet(LookupResult.NotFound::new);
    }

    /** Only payments made with {@link #payLink} exist; the mock never invents payment evidence. */
    @Override
    public LookupResult<PaymentEvidence> getPayment(MerchantRef merchant, String providerPaymentId) {
        if (simulatedLookupUnavailable) {
            return new LookupResult.Unavailable<>("MOCK_LOOKUP_UNAVAILABLE", null);
        }
        PaymentEvidence payment = paymentsById.get(providerPaymentId);
        return payment == null ? new LookupResult.NotFound<>() : new LookupResult.Found<>(payment);
    }

    /**
     * Test/demo knob: a SYNTHETIC customer payment on an existing synthetic link. The link becomes PAID and lists the payment
     * only when {@code paymentStatus} is CAPTURED (like recording 20); otherwise the link keeps its state.
     */
    public String payLink(ProviderAttemptKey key, PaymentEvidenceStatus paymentStatus) {
        PaymentLinkSnapshot link = linksByReference.get(key);
        if (link == null) {
            throw new IllegalStateException("no synthetic link for " + key.value());
        }
        String paymentId = "pay_mock_link_" + key.value().replace("mnd_", "").substring(0, 12) + "_" + paymentSequence.incrementAndGet();
        paymentsById.put(paymentId, new PaymentEvidence(paymentId, paymentStatus, link.amountMinor(), link.currency(), null, null, null,
                Instant.now()));
        if (paymentStatus == PaymentEvidenceStatus.CAPTURED) {
            linksByReference.put(key, new PaymentLinkSnapshot(link.providerLinkId(), key, PaymentLinkState.PAID, link.amountMinor(),
                    link.amountMinor(), link.currency(), link.shortUrl(), List.of(paymentId)));
        }
        log.info("SYNTHETIC event=mock_provider op=pay_link provider_link_id={} provider_payment_id={} status={}",
                link.providerLinkId(), paymentId, paymentStatus);
        return paymentId;
    }

    /** Test knob: move a synthetic link to another state (e.g. EXPIRED / CANCELLED). */
    public void setLinkState(ProviderAttemptKey key, PaymentLinkState state) {
        linksByReference.computeIfPresent(key, (k, link) -> new PaymentLinkSnapshot(link.providerLinkId(), k, state, link.amountMinor(),
                link.amountPaidMinor(), link.currency(), link.shortUrl(), link.capturedPaymentIds()));
    }

    /** Test knob: the create lands (link stored) but the response is lost → OutcomeUnknown. */
    public void setSimulatedLostResponse(boolean lostResponse) {
        this.simulatedLostResponse = lostResponse;
    }

    /** Test knob: every lookup answers Unavailable. */
    public void setSimulatedLookupUnavailable(boolean unavailable) {
        this.simulatedLookupUnavailable = unavailable;
    }

    @Override
    public RequestResult<String> simulateDebit(MerchantRef merchant, UUID campaignId, UUID intentId, ProviderAttemptKey key) {
        record("synthetic_debit", merchant, campaignId, intentId, key);
        RequestResult<String> simulated = simulatedFailure();
        if (simulated != null) {
            return simulated;
        }
        String paymentId = "pay_mock_" + key.value().replace("mnd_", "");
        log.info("SYNTHETIC event=mock_provider op=synthetic_debit provider_payment_id={} attempt_key={}", paymentId, key.value());
        return new RequestResult.Accepted<>(paymentId);
    }

    private <T> RequestResult<T> simulatedFailure() {
        if (simulatedException != null) {
            log.warn("SYNTHETIC event=mock_provider simulated_exception=true");
            throw simulatedException;
        }
        if (simulatedNotSent) {
            return new RequestResult.NotSent<>("MOCK_NOT_SENT");
        }
        return switch (simulatedStatus) {
            case SUCCESS -> null;
            case FAILURE -> new RequestResult.Rejected<>(RejectionKind.VALIDATION, "MOCK_DECLINED", simulatedFailureReason, null);
            case ERROR, PENDING -> {
                log.info("SYNTHETIC event=mock_provider simulated_error={}", simulatedErrorMessage);
                yield new RequestResult.OutcomeUnknown<>("MOCK_PROVIDER_ERROR", null);
            }
        };
    }

    private void record(String operation, MerchantRef merchant, UUID campaignId, UUID intentId, ProviderAttemptKey key) {
        recordedRequests.add(new MockProviderCall(operation, merchant.merchantId(), campaignId, intentId, key));
    }

    public void setSimulatedStatus(PaymentExecutionStatus status) {
        this.simulatedStatus = status;
    }

    public void setSimulatedFailureReason(String reason) {
        this.simulatedFailureReason = reason;
    }

    public void setSimulatedErrorMessage(String message) {
        this.simulatedErrorMessage = message;
    }

    public void setSimulatedException(RuntimeException exception) {
        this.simulatedException = exception;
    }

    public void setSimulatedNotSent(boolean notSent) {
        this.simulatedNotSent = notSent;
    }

    public void reset() {
        this.simulatedStatus = PaymentExecutionStatus.SUCCESS;
        this.simulatedFailureReason = "Simulated payment failure";
        this.simulatedErrorMessage = "Simulated gateway connection timeout";
        this.simulatedException = null;
        this.simulatedNotSent = false;
        this.simulatedLostResponse = false;
        this.simulatedLookupUnavailable = false;
        this.recordedRequests.clear();
        this.linksByReference.clear();
        this.paymentsById.clear();
    }

    public List<MockProviderCall> getRecordedRequests() {
        return new ArrayList<>(recordedRequests);
    }

    public int getInvocationCount() {
        return recordedRequests.size();
    }

    public MockProviderCall getLastRequest() {
        List<MockProviderCall> list = getRecordedRequests();
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
