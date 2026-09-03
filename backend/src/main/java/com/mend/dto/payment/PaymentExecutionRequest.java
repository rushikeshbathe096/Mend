package com.mend.dto.payment;

import com.mend.domain.enums.ActionType;

import java.util.Objects;
import java.util.UUID;

public class PaymentExecutionRequest {

    private final UUID merchantId;
    private final UUID campaignId;
    private final UUID intentId;
    private final String paymentId;
    private final String subscriptionId;
    private final ActionType actionType;
    private final Integer attemptNumber;
    private final String idempotencyKey;

    public PaymentExecutionRequest(
            UUID merchantId,
            UUID campaignId,
            UUID intentId,
            String paymentId,
            String subscriptionId,
            ActionType actionType,
            Integer attemptNumber,
            String idempotencyKey) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId must not be null");
        this.intentId = intentId;
        this.paymentId = paymentId;
        this.subscriptionId = subscriptionId;
        this.actionType = actionType;
        this.attemptNumber = attemptNumber != null ? attemptNumber : 1;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getIntentId() {
        return intentId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public String toString() {
        return "PaymentExecutionRequest{" +
                "merchantId=" + merchantId +
                ", campaignId=" + campaignId +
                ", intentId=" + intentId +
                ", paymentId='" + paymentId + '\'' +
                ", subscriptionId='" + subscriptionId + '\'' +
                ", actionType=" + actionType +
                ", attemptNumber=" + attemptNumber +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                '}';
    }
}
