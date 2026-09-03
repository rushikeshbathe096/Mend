package com.mend.dto.payment;

import java.time.Instant;
import java.util.Objects;

public class PaymentExecutionResult {

    private final PaymentExecutionStatus status;
    private final String externalReference;
    private final String responseCode;
    private final String message;
    private final String idempotencyKey;
    private final Instant executedAt;

    public PaymentExecutionResult(
            PaymentExecutionStatus status,
            String externalReference,
            String responseCode,
            String message,
            String idempotencyKey,
            Instant executedAt) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.externalReference = externalReference;
        this.responseCode = responseCode;
        this.message = message;
        this.idempotencyKey = idempotencyKey;
        this.executedAt = executedAt != null ? executedAt : Instant.now();
    }

    public static PaymentExecutionResult success(String externalReference, String message, String idempotencyKey) {
        return new PaymentExecutionResult(
                PaymentExecutionStatus.SUCCESS,
                externalReference,
                "200_OK",
                message,
                idempotencyKey,
                Instant.now()
        );
    }

    public static PaymentExecutionResult failure(String failureReason, String responseCode, String idempotencyKey) {
        return new PaymentExecutionResult(
                PaymentExecutionStatus.FAILURE,
                null,
                responseCode != null ? responseCode : "PAYMENT_DECLINED",
                failureReason,
                idempotencyKey,
                Instant.now()
        );
    }

    public static PaymentExecutionResult error(String errorMessage, String idempotencyKey) {
        return new PaymentExecutionResult(
                PaymentExecutionStatus.ERROR,
                null,
                "PROVIDER_ERROR",
                errorMessage,
                idempotencyKey,
                Instant.now()
        );
    }

    public PaymentExecutionStatus getStatus() {
        return status;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public String getMessage() {
        return message;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public boolean isSuccess() {
        return status == PaymentExecutionStatus.SUCCESS;
    }

    public boolean isFailure() {
        return status == PaymentExecutionStatus.FAILURE;
    }

    public boolean isError() {
        return status == PaymentExecutionStatus.ERROR;
    }

    @Override
    public String toString() {
        return "PaymentExecutionResult{" +
                "status=" + status +
                ", externalReference='" + externalReference + '\'' +
                ", responseCode='" + responseCode + '\'' +
                ", message='" + message + '\'' +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", executedAt=" + executedAt +
                '}';
    }
}
