package com.mend.dto;

import java.math.BigDecimal;
import java.util.List;

/** Money as in {@link AnalyticsOverviewDto}: exact major-unit display values; unresolvable amounts counted, never summed. */
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
        private final BigDecimal revenueAtRisk;
        private final BigDecimal revenueRecovered;
        private final long amountUnknownCount;

        public FailureClassMetricDto(
                String failureClass,
                long count,
                long recoveredCount,
                double recoveryRatePercent,
                BigDecimal revenueAtRisk,
                BigDecimal revenueRecovered,
                long amountUnknownCount) {
            this.failureClass = failureClass;
            this.count = count;
            this.recoveredCount = recoveredCount;
            this.recoveryRatePercent = recoveryRatePercent;
            this.revenueAtRisk = revenueAtRisk;
            this.revenueRecovered = revenueRecovered;
            this.amountUnknownCount = amountUnknownCount;
        }

        public String getFailureClass() { return failureClass; }
        public long getCount() { return count; }
        /** Recovered by MEND. */
        public long getRecoveredCount() { return recoveredCount; }
        public double getRecoveryRatePercent() { return recoveryRatePercent; }
        public BigDecimal getRevenueAtRisk() { return revenueAtRisk; }
        /** Recovered by MEND. */
        public BigDecimal getRevenueRecovered() { return revenueRecovered; }
        public long getAmountUnknownCount() { return amountUnknownCount; }
    }
}
