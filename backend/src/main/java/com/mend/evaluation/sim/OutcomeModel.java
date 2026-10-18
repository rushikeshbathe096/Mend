package com.mend.evaluation.sim;

import com.mend.taxonomy.PaymentFailureClass;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * M4 U4.5: seeded, documented customer / provider outcome model. Every number here is an ASSUMPTION, never a measurement,
 * so everything the simulator produces is SYNTHETIC (AGENTS.md invariant 20).
 * <ul>
 *   <li>Late capture (the customer pays the original payment anyway, whatever MEND does): probability per class within the
 *       horizon; time exponential with mean {@link #lateCaptureMeanHours}.</li>
 *   <li>Payment link (only when MEND contacts with a link): probability per class that the customer pays it; time
 *       exponential with mean {@link #linkPayMeanHours}; a link not paid within its 72 h validity is never paid.</li>
 *   <li>Retry strategies: debits are not wired (ADR-002). As in production (Razorpay mode), an allowed retry is dispatched
 *       (a DISPATCH row, counted as a contact by the snapshot) and the provider answers NotSupported: no capture. With
 *       {@code retriesWired} a hypothetical retry captures with {@code retrySuccess}, halved when it fires more than 24 h after
 *       eligibility (an assumption, used only to compare retry timings offline). The delay itself comes from production's
 *       {@code ActionPolicy.scheduleDelay}.</li>
 * </ul>
 */
public record OutcomeModel(Map<PaymentFailureClass, Double> lateCapture, Map<PaymentFailureClass, Double> linkPay,
                           double lateCaptureMeanHours, double linkPayMeanHours, boolean retriesWired, double retrySuccess) {

    public static final Duration LINK_TTL = Duration.ofHours(72);

    public OutcomeModel {
        lateCapture = Map.copyOf(lateCapture);
        linkPay = Map.copyOf(linkPay);
    }

    /** Documented defaults (assumptions, SYNTHETIC). */
    public static OutcomeModel defaults() {
        Map<PaymentFailureClass, Double> late = new EnumMap<>(PaymentFailureClass.class);
        Map<PaymentFailureClass, Double> link = new EnumMap<>(PaymentFailureClass.class);
        put(late, link, PaymentFailureClass.INSUFFICIENT_FUNDS, 0.10, 0.18);
        put(late, link, PaymentFailureClass.LIMIT_EXCEEDED, 0.08, 0.20);
        put(late, link, PaymentFailureClass.BANK_TECHNICAL, 0.25, 0.30);
        put(late, link, PaymentFailureClass.GATEWAY_ERROR, 0.25, 0.30);
        put(late, link, PaymentFailureClass.AUTHENTICATION_FAILURE, 0.15, 0.25);
        put(late, link, PaymentFailureClass.CUSTOMER_DECLINED, 0.05, 0.10);
        put(late, link, PaymentFailureClass.INSTRUMENT_INVALID, 0.03, 0.22);
        put(late, link, PaymentFailureClass.MANDATE_INVALID, 0.03, 0.15);
        put(late, link, PaymentFailureClass.UNCLASSIFIED, 0.07, 0.12);
        return new OutcomeModel(late, link, 72.0, 18.0, false, 0.15);
    }

    public OutcomeModel withRetries(boolean wired, double success) {
        return new OutcomeModel(lateCapture, linkPay, lateCaptureMeanHours, linkPayMeanHours, wired, success);
    }

    private static void put(Map<PaymentFailureClass, Double> late, Map<PaymentFailureClass, Double> link, PaymentFailureClass c,
                            double l, double k) {
        late.put(c, l);
        link.put(c, k);
    }

    /** Hours until a late capture of the original payment, or -1 for none (within the horizon). */
    double lateCaptureHours(PaymentFailureClass c, SplittableRandom random, int horizonDays) {
        return draw(lateCapture.getOrDefault(c, 0.0), lateCaptureMeanHours, horizonDays * 24.0, random);
    }

    /** Hours until the customer pays a MEND link, or -1 for never (within the link's validity). */
    double linkPayHours(PaymentFailureClass c, SplittableRandom random) {
        return draw(linkPay.getOrDefault(c, 0.0), linkPayMeanHours, LINK_TTL.toHours(), random);
    }

    /** Whether a (hypothetical, wired) retry fired after {@code delay} captures. A delay above 24 h halves the success. */
    boolean retryCaptures(Duration delay, SplittableRandom random) {
        double p = delay.compareTo(Duration.ofHours(24)) > 0 ? retrySuccess / 2 : retrySuccess;
        return random.nextDouble() < p;
    }

    private static double draw(double probability, double meanHours, double capHours, SplittableRandom random) {
        if (random.nextDouble() >= probability) {
            return -1;
        }
        double hours = -meanHours * StrictMath.log(1 - random.nextDouble());   // StrictMath: identical on every JVM
        return hours <= capHours ? hours : -1;
    }
}
