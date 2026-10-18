package com.mend.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.1 (ADR-004 §1): the holdout hash is deterministic, uniform and bounded. */
class HoldoutAssignerTest {

    private static final byte[] SALT = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            SALT[i] = (byte) i;
        }
    }

    @Test
    @DisplayName("Known answers, computed independently (Python hmac over the same salt and key)")
    void knownAnswers() {
        assertEquals(5989, HoldoutAssigner.bucket(SALT, "pay:pay_KnownAnswer01"));
        assertEquals(6365, HoldoutAssigner.bucket(SALT, "inv:inv_ABC123"));
        assertEquals(2641, HoldoutAssigner.bucket(SALT, "ord:order_XYZ"));
    }

    @Test
    @DisplayName("Deterministic: the same salt and invoice key always give the same bucket and arm")
    void deterministic() {
        int first = HoldoutAssigner.bucket(SALT, "inv:inv_repeat");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, HoldoutAssigner.bucket(SALT.clone(), "inv:inv_repeat"));
        }
        assertEquals(HoldoutAssigner.arm(first, 2000), HoldoutAssigner.arm(first, 2000));
    }

    @Test
    @DisplayName("100 000 seeded keys at rate 0.2: CONTROL share within 5 sigma, buckets uniform over deciles (chi-square, p = 0.001)")
    void distribution() {
        SplittableRandom rnd = new SplittableRandom(20261018L);
        byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) {
            salt[i] = (byte) rnd.nextInt(256);
        }
        int n = 100_000;
        int control = 0;
        int[] deciles = new int[10];
        for (int i = 0; i < n; i++) {
            String key = "inv:inv_" + Long.toHexString(rnd.nextLong());
            int bucket = HoldoutAssigner.bucket(salt, key);
            deciles[bucket / 1000]++;
            if (HoldoutAssigner.arm(bucket, 2000) == Arm.CONTROL) {
                control++;
            }
        }
        double expected = n * 0.2;
        double sigma = Math.sqrt(n * 0.2 * 0.8);
        assertTrue(Math.abs(control - expected) <= 5 * sigma, "CONTROL count " + control + " vs expected " + expected);
        double chi2 = 0;
        for (int count : deciles) {
            chi2 += Math.pow(count - n / 10.0, 2) / (n / 10.0);
        }
        // chi-square critical value, 9 degrees of freedom, p = 0.001
        assertTrue(chi2 < 27.877, "decile chi-square " + chi2);
    }

    @Test
    @DisplayName("Rate 0 (default) gives TREATMENT for every bucket; CONTROL iff bucket < rate")
    void rateBoundaries() {
        for (int bucket : new int[]{0, 1, 4999, 5000, 9999}) {
            assertEquals(Arm.TREATMENT, HoldoutAssigner.arm(bucket, 0));
        }
        assertEquals(Arm.CONTROL, HoldoutAssigner.arm(1999, 2000));
        assertEquals(Arm.TREATMENT, HoldoutAssigner.arm(2000, 2000));
        assertEquals(Arm.CONTROL, HoldoutAssigner.arm(4999, 5000));
        assertEquals(Arm.TREATMENT, HoldoutAssigner.arm(5000, 5000));
    }

    @Test
    @DisplayName("Propensity in basis points: rate for CONTROL, 10 000 - rate for TREATMENT")
    void propensity() {
        assertEquals(2000, HoldoutAssigner.propensityBp(Arm.CONTROL, 2000));
        assertEquals(8000, HoldoutAssigner.propensityBp(Arm.TREATMENT, 2000));
        assertEquals(10_000, HoldoutAssigner.propensityBp(Arm.TREATMENT, 0));
    }

    @Test
    @DisplayName("Rates above 0.5 or below 0, out-of-range buckets and wrong-size salts are refused")
    void invalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> HoldoutAssigner.arm(0, 5001));
        assertThrows(IllegalArgumentException.class, () -> HoldoutAssigner.arm(0, -1));
        assertThrows(IllegalArgumentException.class, () -> HoldoutAssigner.arm(10_000, 100));
        assertThrows(IllegalArgumentException.class, () -> HoldoutAssigner.bucket(new byte[16], "inv:inv_x"));
        assertThrows(IllegalArgumentException.class, () -> HoldoutAssigner.bucket(null, "inv:inv_x"));
        assertThrows(NullPointerException.class, () -> HoldoutAssigner.bucket(SALT, null));
    }

    @Test
    @DisplayName("A different salt gives an unrelated assignment (about 80 % agreement at rate 0.2, not 100 %)")
    void saltMatters() {
        byte[] other = SALT.clone();
        other[0] ^= 1;
        int same = 0;
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            String key = "inv:inv_s" + i;
            if (HoldoutAssigner.arm(HoldoutAssigner.bucket(SALT, key), 2000) == HoldoutAssigner.arm(HoldoutAssigner.bucket(other, key), 2000)) {
                same++;
            }
        }
        // independent arms agree with probability 0.2^2 + 0.8^2 = 0.68
        assertTrue(same > 6500 && same < 7100, "agreement " + same);
    }
}
