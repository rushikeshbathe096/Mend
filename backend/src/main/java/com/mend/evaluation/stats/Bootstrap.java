package com.mend.evaluation.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * M4 U4.4 (ADR-004 §6): seeded percentile bootstrap, resampling each arm independently (stratified by arm). Values are
 * long minor units (or seconds); resample means are exact (BigDecimal, scale 6, HALF_EVEN). The 95 % bounds are the
 * empirical 2.5 % and 97.5 % quantiles (inverse ECDF: index ceil(q·B) − 1). The same seed always gives the same interval.
 */
public final class Bootstrap {

    public static final int MIN_RESAMPLES = 2_000;
    private static final int SCALE = 6;

    private Bootstrap() {
    }

    /** Interval of mean(treatment) − mean(control). */
    public static BigInterval meanDifference(long[] treatment, long[] control, int resamples, long seed) {
        check(resamples);
        if (treatment.length == 0 || control.length == 0) {
            throw new IllegalArgumentException("both arms need at least one value");
        }
        SplittableRandom random = new SplittableRandom(seed);
        BigDecimal[] stats = new BigDecimal[resamples];
        for (int b = 0; b < resamples; b++) {
            stats[b] = mean(resample(treatment, random)).subtract(mean(resample(control, random)));
        }
        return percentile(mean(treatment).subtract(mean(control)), stats);
    }

    /** Interval of the mean of one sample (e.g. paired per-event differences). */
    public static BigInterval meanInterval(long[] values, int resamples, long seed) {
        check(resamples);
        if (values.length == 0) {
            throw new IllegalArgumentException("need at least one value");
        }
        SplittableRandom random = new SplittableRandom(seed);
        BigDecimal[] stats = new BigDecimal[resamples];
        for (int b = 0; b < resamples; b++) {
            stats[b] = mean(resample(values, random));
        }
        return percentile(mean(values), stats);
    }

    /** Interval of the median of one sample. */
    public static BigInterval median(long[] values, int resamples, long seed) {
        check(resamples);
        if (values.length == 0) {
            throw new IllegalArgumentException("need at least one value");
        }
        SplittableRandom random = new SplittableRandom(seed);
        BigDecimal[] stats = new BigDecimal[resamples];
        for (int b = 0; b < resamples; b++) {
            stats[b] = Median.of(resample(values, random));
        }
        return percentile(Median.of(values), stats);
    }

    public static BigDecimal mean(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum = Math.addExact(sum, v);
        }
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(values.length), SCALE, RoundingMode.HALF_EVEN);
    }

    private static long[] resample(long[] values, SplittableRandom random) {
        long[] out = new long[values.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = values[random.nextInt(values.length)];
        }
        return out;
    }

    /** Package-private for the index test: bounds are sorted[ceil(0.025·B) − 1] and sorted[ceil(0.975·B) − 1]. */
    static BigInterval percentile(BigDecimal estimate, BigDecimal[] stats) {
        Arrays.sort(stats);
        int b = stats.length;
        return new BigInterval(estimate, stats[(int) Math.ceil(0.025 * b) - 1], stats[(int) Math.ceil(0.975 * b) - 1]);
    }

    private static void check(int resamples) {
        if (resamples < MIN_RESAMPLES) {
            throw new IllegalArgumentException("at least " + MIN_RESAMPLES + " resamples (ADR-004 §6): " + resamples);
        }
    }

    /** An exact estimate with percentile bounds (minor units or seconds). */
    public record BigInterval(BigDecimal estimate, BigDecimal low, BigDecimal high) {
    }
}
