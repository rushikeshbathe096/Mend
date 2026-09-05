package com.mend.dto;

import java.util.List;

public class CustomerProfileDto {
    private CustomerSummaryDto summary;
    private List<CampaignDto> campaigns;
    private List<PaymentSummaryDto> paymentHistory;
    private List<String> disputeHistory;
    private List<AuditLogDto> auditHistory;

    public CustomerProfileDto() {}

    public CustomerProfileDto(CustomerSummaryDto summary, List<CampaignDto> campaigns,
                              List<PaymentSummaryDto> paymentHistory, List<String> disputeHistory,
                              List<AuditLogDto> auditHistory) {
        this.summary = summary;
        this.campaigns = campaigns;
        this.paymentHistory = paymentHistory;
        this.disputeHistory = disputeHistory;
        this.auditHistory = auditHistory;
    }

    public CustomerSummaryDto getSummary() { return summary; }
    public void setSummary(CustomerSummaryDto summary) { this.summary = summary; }

    public List<CampaignDto> getCampaigns() { return campaigns; }
    public void setCampaigns(List<CampaignDto> campaigns) { this.campaigns = campaigns; }

    public List<PaymentSummaryDto> getPaymentHistory() { return paymentHistory; }
    public void setPaymentHistory(List<PaymentSummaryDto> paymentHistory) { this.paymentHistory = paymentHistory; }

    public List<String> getDisputeHistory() { return disputeHistory; }
    public void setDisputeHistory(List<String> disputeHistory) { this.disputeHistory = disputeHistory; }

    public List<AuditLogDto> getAuditHistory() { return auditHistory; }
    public void setAuditHistory(List<AuditLogDto> auditHistory) { this.auditHistory = auditHistory; }
}
