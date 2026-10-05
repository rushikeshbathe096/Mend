package com.mend.provider.mock;

import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.provider.CreatePaymentLink;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidence;
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

    @Override
    public boolean supportsRecurringDebit() {
        return true;
    }

    @Override
    public RequestResult<PaymentLinkSnapshot> createPaymentLink(MerchantRef merchant, CreatePaymentLink command) {
        record("create_payment_link", merchant, null, command.intentId(), command.key());
        RequestResult<PaymentLinkSnapshot> simulated = simulatedFailure();
        if (simulated != null) {
            return simulated;
        }
        String id = "plink_mock_" + command.key().value().replace("mnd_", "");
        PaymentLinkSnapshot snapshot = linksByReference.computeIfAbsent(command.key(), key -> new PaymentLinkSnapshot(id, key,
                PaymentLinkState.CREATED, command.amount().amountMinor(), 0, command.amount().currency(),
                "https://mock.invalid/" + id, List.of()));
        log.info("SYNTHETIC event=mock_provider op=create_payment_link provider_link_id={} attempt_key={}", snapshot.providerLinkId(),
                command.key().value());
        return new RequestResult.Accepted<>(snapshot);
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> findPaymentLinkByReference(MerchantRef merchant, ProviderAttemptKey key) {
        PaymentLinkSnapshot snapshot = linksByReference.get(key);
        return snapshot == null ? new LookupResult.NotFound<>() : new LookupResult.Found<>(snapshot);
    }

    @Override
    public LookupResult<PaymentLinkSnapshot> getPaymentLink(MerchantRef merchant, String providerLinkId) {
        return linksByReference.values().stream()
                .filter(link -> link.providerLinkId().equals(providerLinkId))
                .findFirst()
                .<LookupResult<PaymentLinkSnapshot>>map(LookupResult.Found::new)
                .orElseGet(LookupResult.NotFound::new);
    }

    /** The mock never invents payment evidence. */
    @Override
    public LookupResult<PaymentEvidence> getPayment(MerchantRef merchant, String providerPaymentId) {
        return new LookupResult.NotFound<>();
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
            case SUCCESS, PENDING -> null;
            case FAILURE -> new RequestResult.Rejected<>(RejectionKind.VALIDATION, "MOCK_DECLINED", simulatedFailureReason, null);
            case ERROR -> {
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
        this.recordedRequests.clear();
        this.linksByReference.clear();
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
