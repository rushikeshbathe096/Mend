package com.mend.dto;

import java.math.BigDecimal;
import java.util.List;

/** Money as in {@link AnalyticsOverviewDto}; "recovered" means recovered by MEND. */
public class AnalyticsStrategyPerformanceDto {

    private final List<StrategyMetricDto> strategies;

    public AnalyticsStrategyPerformanceDto(List<StrategyMetricDto> strategies) {
        this.strategies = strategies;
    }

    public List<StrategyMetricDto> getStrategies() {
        return strategies;
    }

    public static class StrategyMetricDto {
        private final String strategyName;
        private final long totalCampaigns;
        private final long recoveredCampaigns;
        private final double successRatePercent;
        private final BigDecimal revenueRecovered;
        private final long amountUnknownCount;

        public StrategyMetricDto(
                String strategyName,
                long totalCampaigns,
                long recoveredCampaigns,
                double successRatePercent,
                BigDecimal revenueRecovered,
                long amountUnknownCount) {
            this.strategyName = strategyName;
            this.totalCampaigns = totalCampaigns;
            this.recoveredCampaigns = recoveredCampaigns;
            this.successRatePercent = successRatePercent;
            this.revenueRecovered = revenueRecovered;
            this.amountUnknownCount = amountUnknownCount;
        }

        public String getStrategyName() { return strategyName; }
        public long getTotalCampaigns() { return totalCampaigns; }
        public long getRecoveredCampaigns() { return recoveredCampaigns; }
        public double getSuccessRatePercent() { return successRatePercent; }
        public BigDecimal getRevenueRecovered() { return revenueRecovered; }
        public long getAmountUnknownCount() { return amountUnknownCount; }
    }
}
