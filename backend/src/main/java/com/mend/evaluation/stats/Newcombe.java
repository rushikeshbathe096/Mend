package com.mend.evaluation.stats;

/**
 * M4 U4.4 (ADR-004 §6): Newcombe's hybrid score interval (method 10, Newcombe 1998) for p1 − p2, built from the two
 * Wilson intervals. Used for the incremental recovery rate rate(TREATMENT) − rate(CONTROL).
 */
public final class Newcombe {

    private Newcombe() {
    }

    public static Interval difference(long x1, long n1, long x2, long n2) {
        Interval a = Wilson.interval(x1, n1);
        Interval b = Wilson.interval(x2, n2);
        double d = a.estimate() - b.estimate();
        double low = d - Math.sqrt(sq(a.estimate() - a.low()) + sq(b.high() - b.estimate()));
        double high = d + Math.sqrt(sq(a.high() - a.estimate()) + sq(b.estimate() - b.low()));
        return new Interval(d, low, high);
    }

    private static double sq(double v) {
        return v * v;
    }
}
