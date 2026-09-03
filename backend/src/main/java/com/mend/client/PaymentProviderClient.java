package com.mend.client;

import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;

public interface PaymentProviderClient {

    /**
     * Executes a payment recovery retry or customer action via the external payment provider.
     *
     * @param request the execution request details containing tenant ID, action details, and idempotency key
     * @return the execution result detailing success, failure, or provider error
     */
    PaymentExecutionResult executeAction(PaymentExecutionRequest request);
}
