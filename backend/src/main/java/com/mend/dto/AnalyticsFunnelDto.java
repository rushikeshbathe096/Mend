package com.mend.dto;

import java.util.List;

public class AnalyticsFunnelDto {

    private final long totalPaymentFailures;
    private final List<FunnelStageDto> stages;

    public AnalyticsFunnelDto(long totalPaymentFailures, List<FunnelStageDto> stages) {
        this.totalPaymentFailures = totalPaymentFailures;
        this.stages = stages;
    }

    public long getTotalPaymentFailures() {
        return totalPaymentFailures;
    }

    public List<FunnelStageDto> getStages() {
        return stages;
    }

    public static class FunnelStageDto {
        private final String stageName;
        private final long count;
        private final double conversionRatePercent;
        private final long dropOffCount;

        public FunnelStageDto(String stageName, long count, double conversionRatePercent, long dropOffCount) {
            this.stageName = stageName;
            this.count = count;
            this.conversionRatePercent = conversionRatePercent;
            this.dropOffCount = dropOffCount;
        }

        public String getStageName() { return stageName; }
        public long getCount() { return count; }
        public double getConversionRatePercent() { return conversionRatePercent; }
        public long getDropOffCount() { return dropOffCount; }
    }
}
