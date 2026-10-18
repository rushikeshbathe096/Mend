package com.mend.evaluation.stats;

/**
 * M4 U4.4 (design doc § Metrics): planning sample size for detecting rate(T) − rate(C) with a two-sided α = 0.05 test at
 * power 0.8 (unpooled normal approximation), with a CONTROL share h:
 * N = (z₀.₉₇₅ + z₀.₈)² · (p_c(1 − p_c)/h + p_t(1 − p_t)/(1 − h)) / (p_t − p_c)², rounded up; CONTROL = ⌈N·h⌉.
 * A planning calculation from assumed rates, never a result.
 */
public final class SampleSize {

    /** z(0.8), the one-sided quantile for power 0.8. */
    public static final double Z80 = 0.8416212335729143;

    private SampleSize() {
    }

    public static long totalForDifference(double controlRate, double treatmentRate, double holdoutShare) {
        if (controlRate == treatmentRate || holdoutShare <= 0 || holdoutShare >= 1) {
            throw new IllegalArgumentException("need different rates and 0 < holdout share < 1");
        }
        double z = Wilson.Z95 + Z80;
        double variance = controlRate * (1 - controlRate) / holdoutShare + treatmentRate * (1 - treatmentRate) / (1 - holdoutShare);
        double delta = treatmentRate - controlRate;
        return (long) Math.ceil(z * z * variance / (delta * delta));
    }

    public static long controlFor(long total, double holdoutShare) {
        return (long) Math.ceil(total * holdoutShare);
    }
}
