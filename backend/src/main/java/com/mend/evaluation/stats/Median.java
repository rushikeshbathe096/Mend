package com.mend.evaluation.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/** M4 U4.4: exact median of longs (mean of the two middle values for an even count, HALF_EVEN at scale 1). */
public final class Median {

    private Median() {
    }

    public static BigDecimal of(long[] values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("median of nothing");
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return BigDecimal.valueOf(sorted[mid]);
        }
        return BigDecimal.valueOf(sorted[mid - 1]).add(BigDecimal.valueOf(sorted[mid])).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_EVEN);
    }
}
