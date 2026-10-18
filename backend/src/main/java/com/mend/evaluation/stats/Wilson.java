package com.mend.evaluation.stats;

/**
 * M4 U4.4 (ADR-004 §6): Wilson score interval for a binomial proportion (95 %, no continuity correction). Well behaved at
 * small n and near 0 or 1, where the Wald interval collapses.
 */
public final class Wilson {

    /** Two-sided 95 % normal quantile, z(0.975). */
    public static final double Z95 = 1.959963984540054;

    private Wilson() {
    }

    public static Interval interval(long successes, long n) {
        if (n <= 0 || successes < 0 || successes > n) {
            throw new IllegalArgumentException("need 0 <= successes <= n, n > 0: " + successes + "/" + n);
        }
        double p = (double) successes / n;
        double z2 = Z95 * Z95;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denominator;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new Interval(p, Math.max(0.0, centre - half), Math.min(1.0, centre + half));
    }
}
