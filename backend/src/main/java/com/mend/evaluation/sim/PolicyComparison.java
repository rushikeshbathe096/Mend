package com.mend.evaluation.sim;

import com.mend.evaluation.stats.Bootstrap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * M4 U4.5: offline comparison of two policies on the same synthetic stream (SYNTHETIC). Both replays use the same events
 * and the same outcome draws per event (common random numbers), so the per-failure difference is paired; its mean gets a
 * seeded one-sample bootstrap interval. No holdout is involved: every eligible failure is TREATMENT in both runs.
 */
public final class PolicyComparison {

    private PolicyComparison() {
    }

    public record Summary(String name, int failures, long eligible, long contacted, long recovered, long recoveredMinor) {
    }

    public record Comparison(Summary a, Summary b, Bootstrap.BigInterval perFailureDifferenceMinor, long recoveredDifference) {
    }

    public static Comparison compare(String nameA, ReplaySimulator.Result a, String nameB, ReplaySimulator.Result b, int resamples, long seed) {
        List<ReplaySimulator.Outcome> oa = a.outcomes();
        List<ReplaySimulator.Outcome> ob = b.outcomes();
        if (oa.size() != ob.size()) {
            throw new IllegalArgumentException("policies must be replayed on the same stream");
        }
        long[] diff = new long[oa.size()];
        for (int i = 0; i < diff.length; i++) {
            if (!oa.get(i).event().campaignId().equals(ob.get(i).event().campaignId())) {
                throw new IllegalArgumentException("streams are not aligned at " + i);
            }
            diff[i] = ob.get(i).recoveredMinor() - oa.get(i).recoveredMinor();
        }
        Summary sa = summary(nameA, oa);
        Summary sb = summary(nameB, ob);
        return new Comparison(sa, sb, Bootstrap.meanInterval(diff, resamples, seed), sb.recovered() - sa.recovered());
    }

    static Summary summary(String name, List<ReplaySimulator.Outcome> outcomes) {
        long recoveredMinor = outcomes.stream().mapToLong(ReplaySimulator.Outcome::recoveredMinor).sum();
        return new Summary(name, outcomes.size(), outcomes.stream().filter(ReplaySimulator.Outcome::eligible).count(),
                outcomes.stream().filter(ReplaySimulator.Outcome::contacted).count(),
                outcomes.stream().filter(o -> o.firstCapture() != null).count(), recoveredMinor);
    }

    public static String markdown(String title, Comparison c) {
        StringBuilder md = new StringBuilder("### ").append(title).append("\n\n**Label: SYNTHETIC** (simulated outcomes from an assumed model; not revenue)\n\n");
        md.append("| policy | failures | eligible | contacted | recovered | recovered (INR) |\n|---|---|---|---|---|---|\n");
        for (Summary s : List.of(c.a(), c.b())) {
            md.append("| ").append(s.name()).append(" | ").append(s.failures()).append(" | ").append(s.eligible()).append(" | ")
                    .append(s.contacted()).append(" | ").append(s.recovered()).append(" | ").append(rupees(BigDecimal.valueOf(s.recoveredMinor())))
                    .append(" |\n");
        }
        md.append("\nPaired difference (").append(c.b().name()).append(" − ").append(c.a().name()).append(") per failure: ")
                .append(rupees(c.perFailureDifferenceMinor().estimate())).append(" INR, 95 % bootstrap interval ")
                .append(rupees(c.perFailureDifferenceMinor().low())).append(" … ").append(rupees(c.perFailureDifferenceMinor().high()))
                .append(" INR (n = ").append(c.a().failures()).append("); recovered failures ").append(c.recoveredDifference() >= 0 ? "+" : "")
                .append(c.recoveredDifference()).append(".\n");
        return md.toString();
    }

    static String rupees(BigDecimal minor) {
        return minor.setScale(0, RoundingMode.HALF_EVEN).movePointLeft(2).toPlainString();
    }
}
