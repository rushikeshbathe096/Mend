package com.mend.dto;

import java.math.BigDecimal;

/**
 * Money fields are display values in major units (rupees, scale 2) summed exactly from minor units; a campaign whose
 * original amount cannot be resolved is left out of every sum and counted in {@code amountUnknownCount} (never a
 * default value). "Recovered" means recovered by MEND ({@code recovery_attribution = MEND}); late captures of the
 * original payment and RECOVERED rows without an attribution are reported separately.
 */
public class AnalyticsOverviewDto {
    private long totalCampaigns;
    private long recoveredCampaigns;
    private long activeCampaigns;
    private long failedCampaigns;
    private double recoveryRate;
    private long totalAttempts;
    private long totalActionIntents;
    private long successfulIntents;

    private long totalPaymentFailures;
    private BigDecimal revenueAtRisk;
    private BigDecimal amountRecovered;
    private BigDecimal amountRemainingAtRisk;
    private long campaignsEligible;
    private long actionsAttempted;
    private long successfulRecoveries;
    private long failedRecoveryAttempts;
    private long complianceBlocks;
    private long averageIngestionToCampaignLatencyMs;
    private long averageExecutionLatencyMs;

    private long amountUnknownCount;
    private long recoveredLateCaptureCount;
    private BigDecimal amountRecoveredLateCapture;
    private long recoveredAttributionUnknownCount;
    private BigDecimal amountRecoveredAttributionUnknown;

    public AnalyticsOverviewDto() {
    }

    public AnalyticsOverviewDto(
            long totalCampaigns,
            long recoveredCampaigns,
            long activeCampaigns,
            long failedCampaigns,
            double recoveryRate,
            long totalAttempts,
            long totalActionIntents,
            long successfulIntents) {
        this.totalCampaigns = totalCampaigns;
        this.recoveredCampaigns = recoveredCampaigns;
        this.activeCampaigns = activeCampaigns;
        this.failedCampaigns = failedCampaigns;
        this.recoveryRate = recoveryRate;
        this.totalAttempts = totalAttempts;
        this.totalActionIntents = totalActionIntents;
        this.successfulIntents = successfulIntents;
    }

    public long getTotalCampaigns() {
        return totalCampaigns;
    }

    public void setTotalCampaigns(long totalCampaigns) {
        this.totalCampaigns = totalCampaigns;
    }

    public long getRecoveredCampaigns() {
        return recoveredCampaigns;
    }

    public void setRecoveredCampaigns(long recoveredCampaigns) {
        this.recoveredCampaigns = recoveredCampaigns;
    }

    public long getActiveCampaigns() {
        return activeCampaigns;
    }

    public void setActiveCampaigns(long activeCampaigns) {
        this.activeCampaigns = activeCampaigns;
    }

    public long getFailedCampaigns() {
        return failedCampaigns;
    }

    public void setFailedCampaigns(long failedCampaigns) {
        this.failedCampaigns = failedCampaigns;
    }

    public double getRecoveryRate() {
        return recoveryRate;
    }

    public void setRecoveryRate(double recoveryRate) {
        this.recoveryRate = recoveryRate;
    }

    public long getTotalAttempts() {
        return totalAttempts;
    }

    public void setTotalAttempts(long totalAttempts) {
        this.totalAttempts = totalAttempts;
    }

    public long getTotalActionIntents() {
        return totalActionIntents;
    }

    public void setTotalActionIntents(long totalActionIntents) {
        this.totalActionIntents = totalActionIntents;
    }

    public long getSuccessfulIntents() {
        return successfulIntents;
    }

    public void setSuccessfulIntents(long successfulIntents) {
        this.successfulIntents = successfulIntents;
    }

    public long getTotalPaymentFailures() {
        return totalPaymentFailures;
    }

    public void setTotalPaymentFailures(long totalPaymentFailures) {
        this.totalPaymentFailures = totalPaymentFailures;
    }

    public BigDecimal getRevenueAtRisk() {
        return revenueAtRisk;
    }

    public void setRevenueAtRisk(BigDecimal revenueAtRisk) {
        this.revenueAtRisk = revenueAtRisk;
    }

    public BigDecimal getAmountRecovered() {
        return amountRecovered;
    }

    public void setAmountRecovered(BigDecimal amountRecovered) {
        this.amountRecovered = amountRecovered;
    }

    public BigDecimal getAmountRemainingAtRisk() {
        return amountRemainingAtRisk;
    }

    public void setAmountRemainingAtRisk(BigDecimal amountRemainingAtRisk) {
        this.amountRemainingAtRisk = amountRemainingAtRisk;
    }

    public long getCampaignsEligible() {
        return campaignsEligible;
    }

    public void setCampaignsEligible(long campaignsEligible) {
        this.campaignsEligible = campaignsEligible;
    }

    public long getActionsAttempted() {
        return actionsAttempted;
    }

    public void setActionsAttempted(long actionsAttempted) {
        this.actionsAttempted = actionsAttempted;
    }

    public long getSuccessfulRecoveries() {
        return successfulRecoveries;
    }

    public void setSuccessfulRecoveries(long successfulRecoveries) {
        this.successfulRecoveries = successfulRecoveries;
    }

    public long getFailedRecoveryAttempts() {
        return failedRecoveryAttempts;
    }

    public void setFailedRecoveryAttempts(long failedRecoveryAttempts) {
        this.failedRecoveryAttempts = failedRecoveryAttempts;
    }

    public long getComplianceBlocks() {
        return complianceBlocks;
    }

    public void setComplianceBlocks(long complianceBlocks) {
        this.complianceBlocks = complianceBlocks;
    }

    public long getAverageIngestionToCampaignLatencyMs() {
        return averageIngestionToCampaignLatencyMs;
    }

    public void setAverageIngestionToCampaignLatencyMs(long averageIngestionToCampaignLatencyMs) {
        this.averageIngestionToCampaignLatencyMs = averageIngestionToCampaignLatencyMs;
    }

    public long getAverageExecutionLatencyMs() {
        return averageExecutionLatencyMs;
    }

    public void setAverageExecutionLatencyMs(long averageExecutionLatencyMs) {
        this.averageExecutionLatencyMs = averageExecutionLatencyMs;
    }

    public long getAmountUnknownCount() {
        return amountUnknownCount;
    }

    public void setAmountUnknownCount(long amountUnknownCount) {
        this.amountUnknownCount = amountUnknownCount;
    }

    public long getRecoveredLateCaptureCount() {
        return recoveredLateCaptureCount;
    }

    public void setRecoveredLateCaptureCount(long recoveredLateCaptureCount) {
        this.recoveredLateCaptureCount = recoveredLateCaptureCount;
    }

    public BigDecimal getAmountRecoveredLateCapture() {
        return amountRecoveredLateCapture;
    }

    public void setAmountRecoveredLateCapture(BigDecimal amountRecoveredLateCapture) {
        this.amountRecoveredLateCapture = amountRecoveredLateCapture;
    }

    public long getRecoveredAttributionUnknownCount() {
        return recoveredAttributionUnknownCount;
    }

    public void setRecoveredAttributionUnknownCount(long recoveredAttributionUnknownCount) {
        this.recoveredAttributionUnknownCount = recoveredAttributionUnknownCount;
    }

    public BigDecimal getAmountRecoveredAttributionUnknown() {
        return amountRecoveredAttributionUnknown;
    }

    public void setAmountRecoveredAttributionUnknown(BigDecimal amountRecoveredAttributionUnknown) {
        this.amountRecoveredAttributionUnknown = amountRecoveredAttributionUnknown;
    }
}
