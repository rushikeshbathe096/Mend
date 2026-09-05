package com.mend.dto;

public class StartRecoveryRequest {
    private String paymentId;
    private String customerIdHash;
    private String subscriptionId;

    public StartRecoveryRequest() {
    }

    public StartRecoveryRequest(String paymentId, String customerIdHash, String subscriptionId) {
        this.paymentId = paymentId;
        this.customerIdHash = customerIdHash;
        this.subscriptionId = subscriptionId;
    }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getCustomerIdHash() { return customerIdHash; }
    public void setCustomerIdHash(String customerIdHash) { this.customerIdHash = customerIdHash; }

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
}
