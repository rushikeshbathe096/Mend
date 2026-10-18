package com.mend.evaluation.sim;

import com.mend.taxonomy.ProviderFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * M4 U4.5: seeded synthetic failure stream (SYNTHETIC). Same seed and size → the same events.
 * <ul>
 *   <li>Failure fields: Razorpay {@code error_reason} values drawn with the weights in {@link #REASONS} (documented reasons of
 *       each taxonomy class plus documented-but-unmapped declines), classified later by the production taxonomy.</li>
 *   <li>Amount (paise): {@link #AMOUNTS} with their weights (199, 499, 999, 1 999, 4 999 INR).</li>
 *   <li>First eligibility: uniform over the window; one invoice per failure ({@code inv:inv_SYN<seed>_<n>}), except that a share
 *       {@code reuseShare} of failures reuses an earlier failure's invoice (a second failed payment of the same invoice).</li>
 *   <li>Ids: name-based UUIDs of (seed, index), so a re-run gives the same ids.</li>
 * </ul>
 */
public final class SyntheticGenerator {

    record Weighted(String value, double weight) {
    }

    static final List<Weighted> REASONS = List.of(
            new Weighted("insufficient_funds", 0.28),
            new Weighted("transaction_limit_exceeded", 0.05),
            new Weighted("bank_technical_error", 0.08),
            new Weighted("gateway_technical_error", 0.06),
            new Weighted("incorrect_otp", 0.10),
            new Weighted("payment_cancelled", 0.08),
            new Weighted("card_expired", 0.07),
            new Weighted("mandate_creation_failed", 0.03),
            new Weighted("card_declined", 0.15),        // documented, no actor: UNCLASSIFIED
            new Weighted("payment_failed", 0.10));      // documented, no actor: UNCLASSIFIED

    static final long[] AMOUNTS = {19_900, 49_900, 99_900, 199_900, 499_900};
    static final double[] AMOUNT_WEIGHTS = {0.30, 0.30, 0.20, 0.15, 0.05};

    private SyntheticGenerator() {
    }

    public static List<ReplayEvent> generate(long seed, int size, UUID merchantId, Instant windowStart, Duration window) {
        return generate(seed, size, merchantId, windowStart, window, 0.0);
    }

    public static List<ReplayEvent> generate(long seed, int size, UUID merchantId, Instant windowStart, Duration window, double reuseShare) {
        SplittableRandom random = new SplittableRandom(seed);
        List<ReplayEvent> events = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String reason = pick(random);
            long amount = AMOUNTS[pickIndex(AMOUNT_WEIGHTS, random)];
            Instant eligibleAt = windowStart.plusSeconds((long) (random.nextDouble() * window.toSeconds()));
            UUID campaignId = UUID.nameUUIDFromBytes(("sim:" + seed + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String invoice = "inv:inv_SYN" + seed + "_" + i;
            if (i > 0 && random.nextDouble() < reuseShare) {
                ReplayEvent earlier = events.get(random.nextInt(i));
                invoice = earlier.invoiceKey();
                amount = earlier.amountMinor();
            }
            events.add(new ReplayEvent(merchantId, campaignId, invoice, ProviderFailure.of("BAD_REQUEST_ERROR", reason, "customer"),
                    null, null, amount, eligibleAt));
        }
        return events;
    }

    private static String pick(SplittableRandom random) {
        double[] weights = REASONS.stream().mapToDouble(Weighted::weight).toArray();
        return REASONS.get(pickIndex(weights, random)).value();
    }

    private static int pickIndex(double[] weights, SplittableRandom random) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double u = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            u -= weights[i];
            if (u < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }
}
