package com.mend.client;

import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@ConditionalOnProperty(name = "mend.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentProviderClient implements PaymentProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProviderClient.class);

    private PaymentExecutionStatus defaultSimulatedStatus = PaymentExecutionStatus.SUCCESS;
    private String simulatedFailureReason = "Simulated payment failure";
    private String simulatedErrorMessage = "Simulated gateway connection timeout";
    private final ConcurrentLinkedQueue<PaymentExecutionRequest> recordedRequests = new ConcurrentLinkedQueue<>();

    @Override
    public PaymentExecutionResult executeAction(PaymentExecutionRequest request) {
        log.info("MockPaymentProviderClient processing request for merchant='{}', campaign='{}', action='{}', idempotencyKey='{}'",
                request.getMerchantId(), request.getCampaignId(), request.getActionType(), request.getIdempotencyKey());

        recordedRequests.add(request);

        switch (defaultSimulatedStatus) {
            case SUCCESS:
                String mockRef = "mock_ref_" + Math.abs(request.getIdempotencyKey().hashCode());
                return PaymentExecutionResult.success(
                        mockRef,
                        "Mock payment retry executed successfully for idempotencyKey=" + request.getIdempotencyKey(),
                        request.getIdempotencyKey()
                );
            case FAILURE:
                return PaymentExecutionResult.failure(
                        simulatedFailureReason,
                        "MOCK_DECLINED",
                        request.getIdempotencyKey()
                );
            case ERROR:
                return PaymentExecutionResult.error(
                        simulatedErrorMessage,
                        request.getIdempotencyKey()
                );
            default:
                return PaymentExecutionResult.error("Unknown simulated status", request.getIdempotencyKey());
        }
    }

    public void setSimulatedStatus(PaymentExecutionStatus status) {
        this.defaultSimulatedStatus = status;
    }

    public void setSimulatedFailureReason(String reason) {
        this.simulatedFailureReason = reason;
    }

    public void setSimulatedErrorMessage(String message) {
        this.simulatedErrorMessage = message;
    }

    public void reset() {
        this.defaultSimulatedStatus = PaymentExecutionStatus.SUCCESS;
        this.simulatedFailureReason = "Simulated payment failure";
        this.simulatedErrorMessage = "Simulated gateway connection timeout";
        this.recordedRequests.clear();
    }

    public List<PaymentExecutionRequest> getRecordedRequests() {
        return new ArrayList<>(recordedRequests);
    }

    public int getInvocationCount() {
        return recordedRequests.size();
    }

    public PaymentExecutionRequest getLastRequest() {
        List<PaymentExecutionRequest> list = getRecordedRequests();
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
