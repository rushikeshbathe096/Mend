package com.mend.evaluation.stats;

/**
 * M4 U4.4 (ADR-004 §6): sample-ratio mismatch check. Chi-square goodness of fit of the observed CONTROL / TREATMENT counts
 * against the configured holdout rate (1 degree of freedom); p = erfc(√(χ²/2)). Flag at p &lt; 0.001.
 */
public final class ChiSquare {

    public static final double SRM_ALPHA = 0.001;

    private ChiSquare() {
    }

    public record Result(double statistic, double pValue) {
        public boolean mismatch() {
            return pValue < SRM_ALPHA;
        }
    }

    public static Result sampleRatio(long control, long total, int holdoutRateBp) {
        if (total <= 0 || control < 0 || control > total || holdoutRateBp <= 0 || holdoutRateBp >= 10_000) {
            throw new IllegalArgumentException("need 0 <= control <= total, total > 0 and 0 < rate < 10000 bp");
        }
        double expectedControl = total * holdoutRateBp / 10_000.0;
        double expectedTreatment = total - expectedControl;
        double treatment = total - control;
        double stat = sq(control - expectedControl) / expectedControl + sq(treatment - expectedTreatment) / expectedTreatment;
        return new Result(stat, erfc(Math.sqrt(stat / 2)));
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Complementary error function (Numerical Recipes erfcc, fractional error below 1.2e-7). */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? ans : 2.0 - ans;
    }
}
