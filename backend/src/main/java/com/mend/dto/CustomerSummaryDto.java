package com.mend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class CustomerSummaryDto {
    private String customerIdHash;
    private UUID merchantId;
    private long totalCampaigns;
    private long activeCampaigns;
    private long recoveredCampaigns;
    private BigDecimal totalFailedAmount;
    private BigDecimal totalRecoveredAmount;
    private List<String> riskSignals;
    private Instant lastActivityAt;
    // Reported separately, as in analytics: RECOVERED but not by MEND (late capture of the original payment),
    // or RECOVERED with no attribution (before V15). Not part of recoveredCampaigns / totalRecoveredAmount.
    private long recoveredLateCaptureCount;
    private BigDecimal amountRecoveredLateCapture;
    private long recoveredAttributionUnknownCount;
    private BigDecimal amountRecoveredAttributionUnknown;
    // Campaigns with no resolvable amount (as analytics amountUnknownCount): excluded from every amount above.
    private long amountUnknownCount;
    // Mock-mode simulated recoveries (attribution SYNTHETIC), as analytics recoveredSyntheticCount: never MEND's.
    private long recoveredSyntheticCount;
    private BigDecimal amountRecoveredSynthetic;

    public CustomerSummaryDto() {}

    public CustomerSummaryDto(String customerIdHash, UUID merchantId, long totalCampaigns, long activeCampaigns,
                              long recoveredCampaigns, BigDecimal totalFailedAmount, BigDecimal totalRecoveredAmount,
                              List<String> riskSignals, Instant lastActivityAt) {
        this.customerIdHash = customerIdHash;
        this.merchantId = merchantId;
        this.totalCampaigns = totalCampaigns;
        this.activeCampaigns = activeCampaigns;
        this.recoveredCampaigns = recoveredCampaigns;
        this.totalFailedAmount = totalFailedAmount;
        this.totalRecoveredAmount = totalRecoveredAmount;
        this.riskSignals = riskSignals;
        this.lastActivityAt = lastActivityAt;
    }

    public String getCustomerIdHash() { return customerIdHash; }
    public void setCustomerIdHash(String customerIdHash) { this.customerIdHash = customerIdHash; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public long getTotalCampaigns() { return totalCampaigns; }
    public void setTotalCampaigns(long totalCampaigns) { this.totalCampaigns = totalCampaigns; }

    public long getActiveCampaigns() { return activeCampaigns; }
    public void setActiveCampaigns(long activeCampaigns) { this.activeCampaigns = activeCampaigns; }

    public long getRecoveredCampaigns() { return recoveredCampaigns; }
    public void setRecoveredCampaigns(long recoveredCampaigns) { this.recoveredCampaigns = recoveredCampaigns; }

    public BigDecimal getTotalFailedAmount() { return totalFailedAmount; }
    public void setTotalFailedAmount(BigDecimal totalFailedAmount) { this.totalFailedAmount = totalFailedAmount; }

    public BigDecimal getTotalRecoveredAmount() { return totalRecoveredAmount; }
    public void setTotalRecoveredAmount(BigDecimal totalRecoveredAmount) { this.totalRecoveredAmount = totalRecoveredAmount; }

    public List<String> getRiskSignals() { return riskSignals; }
    public void setRiskSignals(List<String> riskSignals) { this.riskSignals = riskSignals; }

    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }

    public long getRecoveredLateCaptureCount() { return recoveredLateCaptureCount; }
    public void setRecoveredLateCaptureCount(long recoveredLateCaptureCount) { this.recoveredLateCaptureCount = recoveredLateCaptureCount; }

    public BigDecimal getAmountRecoveredLateCapture() { return amountRecoveredLateCapture; }
    public void setAmountRecoveredLateCapture(BigDecimal amountRecoveredLateCapture) { this.amountRecoveredLateCapture = amountRecoveredLateCapture; }

    public long getRecoveredAttributionUnknownCount() { return recoveredAttributionUnknownCount; }
    public void setRecoveredAttributionUnknownCount(long recoveredAttributionUnknownCount) { this.recoveredAttributionUnknownCount = recoveredAttributionUnknownCount; }

    public BigDecimal getAmountRecoveredAttributionUnknown() { return amountRecoveredAttributionUnknown; }
    public void setAmountRecoveredAttributionUnknown(BigDecimal amountRecoveredAttributionUnknown) { this.amountRecoveredAttributionUnknown = amountRecoveredAttributionUnknown; }

    public long getAmountUnknownCount() { return amountUnknownCount; }
    public void setAmountUnknownCount(long amountUnknownCount) { this.amountUnknownCount = amountUnknownCount; }

    public long getRecoveredSyntheticCount() { return recoveredSyntheticCount; }
    public void setRecoveredSyntheticCount(long recoveredSyntheticCount) { this.recoveredSyntheticCount = recoveredSyntheticCount; }

    public BigDecimal getAmountRecoveredSynthetic() { return amountRecoveredSynthetic; }
    public void setAmountRecoveredSynthetic(BigDecimal amountRecoveredSynthetic) { this.amountRecoveredSynthetic = amountRecoveredSynthetic; }
}
