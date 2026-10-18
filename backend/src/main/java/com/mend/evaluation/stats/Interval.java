package com.mend.evaluation.stats;

/** M4 U4.4: a point estimate with a two-sided 95 % interval. */
public record Interval(double estimate, double low, double high) {

    public Interval {
        if (Double.isNaN(estimate) || Double.isNaN(low) || Double.isNaN(high) || low > high) {
            throw new IllegalArgumentException("invalid interval " + low + " .. " + high + " around " + estimate);
        }
    }
}
