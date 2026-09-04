package com.mend.dto;

import java.util.List;

public class AnalyticsFailureBreakdownDto {

    private final List<FailureClassMetricDto> failureClasses;

    public AnalyticsFailureBreakdownDto(List<FailureClassMetricDto> failureClasses) {
        this.failureClasses = failureClasses;
    }

    public List<FailureClassMetricDto> getFailureClasses() {
        return failureClasses;
    }

    public static class FailureClassMetricDto {
        private final String failureClass;
        private final long count;
        private final long recoveredCount;
        private final double recoveryRatePercent;
        private final double revenueAtRisk;
        private final double revenueRecovered;

        public FailureClassMetricDto(
                String failureClass,
                long count,
                long recoveredCount,
                double recoveryRatePercent,
                double revenueAtRisk,
                double revenueRecovered) {
            this.failureClass = failureClass;
            this.count = count;
            this.recoveredCount = recoveredCount;
            this.recoveryRatePercent = recoveryRatePercent;
            this.revenueAtRisk = revenueAtRisk;
            this.revenueRecovered = revenueRecovered;
        }

        public String getFailureClass() { return failureClass; }
        public long getCount() { return count; }
        public long getRecoveredCount() { return recoveredCount; }
        public double getRecoveryRatePercent() { return recoveryRatePercent; }
        public double getRevenueAtRisk() { return revenueAtRisk; }
        public double getRevenueRecovered() { return revenueRecovered; }
    }
}
