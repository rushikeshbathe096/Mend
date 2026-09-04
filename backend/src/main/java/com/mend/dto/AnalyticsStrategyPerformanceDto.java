package com.mend.dto;

import java.util.List;

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
        private final double revenueRecovered;

        public StrategyMetricDto(
                String strategyName,
                long totalCampaigns,
                long recoveredCampaigns,
                double successRatePercent,
                double revenueRecovered) {
            this.strategyName = strategyName;
            this.totalCampaigns = totalCampaigns;
            this.recoveredCampaigns = recoveredCampaigns;
            this.successRatePercent = successRatePercent;
            this.revenueRecovered = revenueRecovered;
        }

        public String getStrategyName() { return strategyName; }
        public long getTotalCampaigns() { return totalCampaigns; }
        public long getRecoveredCampaigns() { return recoveredCampaigns; }
        public double getSuccessRatePercent() { return successRatePercent; }
        public double getRevenueRecovered() { return revenueRecovered; }
    }
}
