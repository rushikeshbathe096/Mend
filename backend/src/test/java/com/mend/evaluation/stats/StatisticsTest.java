package com.mend.evaluation.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.4: statistics against hand-computed / published values (ADR-004 §6). */
class StatisticsTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("Wilson 95 %: 10/100 = (0.055229, 0.174366); 0/30 = (0, 0.113513); 56/70 = (0.691834, 0.876953)")
    void wilson() {
        Interval a = Wilson.interval(10, 100);
        assertEquals(0.1, a.estimate(), EPS);
        assertEquals(0.0552291, a.low(), EPS);
        assertEquals(0.1743657, a.high(), EPS);
        Interval zero = Wilson.interval(0, 30);
        assertEquals(0.0, zero.low(), EPS);
        assertEquals(0.1135134, zero.high(), EPS);
        Interval b = Wilson.interval(56, 70);
        assertEquals(0.6918336, b.low(), EPS);
        assertEquals(0.8769526, b.high(), EPS);
        assertThrows(IllegalArgumentException.class, () -> Wilson.interval(5, 0));
        assertThrows(IllegalArgumentException.class, () -> Wilson.interval(6, 5));
    }

    @Test
    @DisplayName("Newcombe method 10, published example (Newcombe 1998): 56/70 − 48/80 = 0.2000, 95 % CI (0.0524, 0.3339)")
    void newcombePublishedExample() {
        Interval d = Newcombe.difference(56, 70, 48, 80);
        assertEquals(0.2, d.estimate(), EPS);
        assertEquals(0.0524315, d.low(), EPS);
        assertEquals(0.3338727, d.high(), EPS);
    }

    @Test
    @DisplayName("Newcombe is antisymmetric and contains 0 for equal rates")
    void newcombeSymmetry() {
        Interval ab = Newcombe.difference(30, 100, 20, 100);
        Interval ba = Newcombe.difference(20, 100, 30, 100);
        assertEquals(-ab.estimate(), ba.estimate(), EPS);
        assertEquals(-ab.low(), ba.high(), EPS);
        Interval same = Newcombe.difference(15, 100, 15, 100);
        assertTrue(same.low() < 0 && same.high() > 0);
    }

    @Test
    @DisplayName("Median: odd, even (exact half), single value")
    void median() {
        assertEquals(new BigDecimal("3"), Median.of(new long[]{5, 1, 3}));
        assertEquals(new BigDecimal("2.5"), Median.of(new long[]{4, 1, 3, 2}));
        assertEquals(new BigDecimal("7"), Median.of(new long[]{7}));
        assertThrows(IllegalArgumentException.class, () -> Median.of(new long[0]));
    }

    @Test
    @DisplayName("Bootstrap: seeded = reproducible; constant data gives a zero-width interval; exact means; ≥ 2 000 resamples")
    void bootstrap() {
        long[] t = {0, 0, 49_900, 0, 10_000, 0, 0, 25_000, 0, 0};
        long[] c = {0, 0, 0, 10_000, 0, 0, 0, 0, 0, 0};
        Bootstrap.BigInterval a = Bootstrap.meanDifference(t, c, 2_000, 42L);
        Bootstrap.BigInterval b = Bootstrap.meanDifference(t, c, 2_000, 42L);
        assertEquals(a, b);
        assertEquals(new BigDecimal("7490.000000"), a.estimate(), "(84 900 − 10 000) / 10 exactly");
        assertTrue(a.low().compareTo(a.estimate()) <= 0 && a.high().compareTo(a.estimate()) >= 0);
        assertNotEquals(a, Bootstrap.meanDifference(t, c, 2_000, 43L), "another seed resamples differently");

        Bootstrap.BigInterval flat = Bootstrap.meanDifference(new long[]{5, 5, 5}, new long[]{2, 2}, 2_000, 1L);
        assertEquals(0, flat.low().compareTo(new BigDecimal("3")));
        assertEquals(0, flat.high().compareTo(new BigDecimal("3")));

        Bootstrap.BigInterval med = Bootstrap.median(new long[]{10, 20, 30, 40, 50}, 2_000, 7L);
        assertEquals(new BigDecimal("30"), med.estimate());
        assertTrue(med.low().compareTo(new BigDecimal("10")) >= 0 && med.high().compareTo(new BigDecimal("50")) <= 0);
        assertThrows(IllegalArgumentException.class, () -> Bootstrap.meanDifference(t, c, 1_999, 1L));
    }

    @Test
    @DisplayName("Bootstrap percentile indices: B = 2 000 takes the 50th and 1 950th sorted values")
    void bootstrapIndices() {
        // one value 0 or 1 per arm: the resampled difference mean has a known distribution; check the order statistics only
        long[] t = {0, 1};
        long[] c = {0};
        Bootstrap.BigInterval i = Bootstrap.meanDifference(t, c, 2_000, 99L);
        assertEquals(0, i.low().compareTo(BigDecimal.ZERO), "P(both draws 0) = 0.25 > 0.025");
        assertEquals(0, i.high().compareTo(BigDecimal.ONE), "P(both draws 1) = 0.25 > 0.025");
    }

    @Test
    @DisplayName("Percentile indices: B = 2 000 sorted values 1..2000 give bounds 50 and 1 950")
    void percentileIndexExact() {
        BigDecimal[] stats = new BigDecimal[2000];
        for (int i = 0; i < 2000; i++) {
            stats[i] = BigDecimal.valueOf(2000 - i);   // reversed: percentile sorts
        }
        Bootstrap.BigInterval i = Bootstrap.percentile(BigDecimal.ZERO, stats);
        assertEquals(0, i.low().compareTo(BigDecimal.valueOf(50)));
        assertEquals(0, i.high().compareTo(BigDecimal.valueOf(1950)));
    }

    @Test
    @DisplayName("Newcombe at the edges: 0/30 − 0/30 and 30/30 − 0/30 stay inside [−1, 1]")
    void newcombeEdges() {
        Interval zero = Newcombe.difference(0, 30, 0, 30);
        assertEquals(0.0, zero.estimate(), EPS);
        assertTrue(zero.low() < 0 && zero.high() > 0 && zero.low() >= -1 && zero.high() <= 1);
        Interval full = Newcombe.difference(30, 30, 0, 30);
        assertEquals(1.0, full.estimate(), EPS);
        assertTrue(full.high() <= 1.0 + EPS && full.low() > 0.8);
    }

    @Test
    @DisplayName("erfc accuracy at small p: χ² 10.8276 → p 0.001, χ² 15.1367 → p 0.0001 (relative 1e-6)")
    void erfcSmallP() {
        assertEquals(1e-3, ChiSquare.erfc(Math.sqrt(10.827566170662733 / 2)), 1e-9);
        assertEquals(1e-4, ChiSquare.erfc(Math.sqrt(15.136705226623606 / 2)), 1e-10);
    }

    @ParameterizedTest(name = "control {0} vs treatment {1} at holdout {2}: N = {3}, CONTROL = {4}")
    @CsvSource({
            "0.10, 0.15, 0.1, 3271, 328",
            "0.10, 0.15, 0.2, 1914, 383",
            "0.10, 0.15, 0.5, 1366, 683",
            "0.20, 0.25, 0.2, 3248, 650",
            "0.10, 0.12, 0.2, 11421, 2285"})
    @DisplayName("Sample-size table of the design doc (planning calculation, unpooled normal approximation)")
    void sampleSize(double pc, double pt, double h, long total, long control) {
        long n = SampleSize.totalForDifference(pc, pt, h);
        assertEquals(total, n);
        assertEquals(control, SampleSize.controlFor(n, h));
    }

    @Test
    @DisplayName("SRM chi-square: exact ratio gives χ² 0, p 1; 230/1000 at 0.2 gives χ² 5.625, p 0.0177; 3.8415 gives p 0.05")
    void sampleRatio() {
        ChiSquare.Result exact = ChiSquare.sampleRatio(200, 1000, 2000);
        assertEquals(0.0, exact.statistic(), EPS);
        assertEquals(1.0, exact.pValue(), 1e-6);
        ChiSquare.Result off = ChiSquare.sampleRatio(230, 1000, 2000);
        assertEquals(5.625, off.statistic(), EPS);
        assertEquals(0.0177061, off.pValue(), 1e-6);
        assertFalse(off.mismatch());
        assertEquals(0.05, ChiSquare.erfc(Math.sqrt(3.841458820694124 / 2)), 1e-6);
        assertTrue(ChiSquare.sampleRatio(300, 1000, 2000).mismatch(), "30 % CONTROL at a 20 % rate");
    }
}
