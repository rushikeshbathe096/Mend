package com.mend.evaluation.sim;

import com.mend.evaluation.snapshot.SnapshotRow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * M4 U4.5 review: replay of a snapshot's campaigns (a counterfactual policy run on real-shaped inputs; SYNTHETIC). A snapshot
 * keeps only the class with its taxonomy version (no raw provider fields), pseudonymous references and the eligibility day,
 * so the replay uses: the stored class and version (a legacy row without a version fails closed), the invoice reference as
 * the invoice key, the eligibility day plus the row's rank within the day in seconds (exact times are not in a snapshot),
 * and the known amount (0 when unknown). Observed outcomes are not reused: the outcome model draws them, because a
 * different policy would have changed them.
 */
public final class SnapshotReplay {

    private SnapshotReplay() {
    }

    public static List<ReplayEvent> events(List<SnapshotRow> rows, UUID merchantId) {
        List<SnapshotRow> sorted = rows.stream().sorted(Comparator.comparing(SnapshotRow::eligibleDay).thenComparing(SnapshotRow::campaignRef)).toList();
        List<ReplayEvent> events = new ArrayList<>();
        String day = null;
        int rank = 0;
        for (SnapshotRow r : sorted) {
            rank = r.eligibleDay().equals(day) ? rank + 1 : 0;
            day = r.eligibleDay();
            Instant eligibleAt = LocalDate.parse(r.eligibleDay()).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(rank);
            events.add(new ReplayEvent(merchantId, UUID.nameUUIDFromBytes(("snapshot:" + r.campaignRef()).getBytes(StandardCharsets.UTF_8)),
                    "ref:" + r.invoiceRef(), null, r.failureClass(), r.taxonomyVersion(), r.amountMinor() == null ? 0L : r.amountMinor(), eligibleAt));
        }
        return events;
    }
}
