package com.mend.evaluation.snapshot;

import com.mend.evaluation.EvidenceLabel;
import com.mend.evaluation.snapshot.CampaignFacts.Capture;
import com.mend.evaluation.snapshot.CampaignFacts.CaptureSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M4 U4.3 (ADR-004 §5): facts as of {@code as_of} → PII-free, deterministically ordered snapshot rows. Pure: the same facts,
 * salts and parameters always give the same rows. The outcome is provider capture evidence within the horizon after first
 * eligibility, read the same way for both arms (U4.0 review BLOCKER 1).
 */
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    public record Content(List<SnapshotRow> campaigns, List<DecisionRow> decisions) {
    }

    public static Content build(List<CampaignFacts> facts, List<DecisionFacts> decisions, Pseudonyms ids, SnapshotParams params) {
        Duration horizon = Duration.ofDays(params.horizonDays());
        List<SnapshotRow> rows = new ArrayList<>();
        Map<UUID, Instant> eligibleAt = new HashMap<>();
        Map<String, List<CampaignFacts>> byInvoice = new HashMap<>();
        for (CampaignFacts f : facts) {
            byInvoice.computeIfAbsent(invoiceKey(f), k -> new ArrayList<>()).add(f);
        }
        Map<String, Invoice> invoices = new HashMap<>();
        byInvoice.forEach((key, group) -> invoices.put(key, invoice(group, params, horizon)));
        for (CampaignFacts f : facts) {
            eligibleAt.put(f.campaignId(), f.eligibleAt());
            rows.add(row(f, invoices.get(invoiceKey(f)), ids, params, horizon));
        }
        rows.sort(Comparator.comparing(SnapshotRow::merchantRef).thenComparing(SnapshotRow::campaignRef));

        List<DecisionRow> decisionRows = new ArrayList<>();
        for (DecisionFacts d : decisions) {
            Instant base = eligibleAt.get(d.campaignId());
            if (base == null || d.decidedAt().isAfter(params.asOf())) {
                continue; // not a campaign of this snapshot, or after as_of
            }
            decisionRows.add(new DecisionRow(SnapshotRow.SCHEMA_VERSION, ids.merchantRef(d.merchantId()),
                    ids.ref(d.merchantId(), "campaign", d.campaignId().toString()),
                    d.intentId() == null ? null : ids.ref(d.merchantId(), "intent", d.intentId().toString()),
                    d.decisionPoint(), d.chosen(), d.reasonCode(), d.alternatives(), d.propensityBp(), d.taxonomyVersion(),
                    d.strategyVersion(), d.complianceVersion(), d.assignmentVersion(), Duration.between(base, d.decidedAt()).toSeconds()));
        }
        decisionRows.sort(Comparator.comparing(DecisionRow::merchantRef).thenComparing(DecisionRow::campaignRef)
                .thenComparingLong(DecisionRow::offsetS).thenComparing(DecisionRow::decisionPoint).thenComparing(DecisionRow::chosen)
                .thenComparing(r -> r.intentRef() == null ? "" : r.intentRef()));
        return new Content(List.copyOf(rows), List.copyOf(decisionRows));
    }

    /** U4.4 review: the invoice is the analysis unit; its outcome spans all its campaigns (exact times, before pseudonymising). */
    private record Invoice(UUID firstCampaign, int campaigns, boolean mature, Capture firstCapture, Instant firstEligible, int contacts) {
    }

    private static String invoiceKey(CampaignFacts f) {
        return f.merchantId() + "|" + (f.invoiceKey() != null ? f.invoiceKey() : "cmp:" + f.campaignId());
    }

    private static Invoice invoice(List<CampaignFacts> group, SnapshotParams params, Duration horizon) {
        CampaignFacts first = group.stream().min(Comparator.comparing(CampaignFacts::eligibleAt).thenComparing(CampaignFacts::campaignId))
                .orElseThrow();
        Instant start = first.eligibleAt();
        Instant end = start.plus(horizon);
        Capture capture = group.stream().flatMap(f -> f.captures().stream())
                .filter(c -> !c.at().isBefore(start) && !c.at().isAfter(end) && !c.at().isAfter(params.asOf()))
                .min(Comparator.comparing(Capture::at).thenComparing(c -> c.source().name())).orElse(null);
        int contacts = (int) group.stream().flatMap(f -> f.dispatchTimes().stream()).filter(t -> !t.isAfter(params.asOf())).count();
        return new Invoice(first.campaignId(), group.size(), !end.isAfter(params.asOf()), capture, start, contacts);
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static SnapshotRow row(CampaignFacts f, Invoice invoice, Pseudonyms ids, SnapshotParams params, Duration horizon) {
        Instant eligible = f.eligibleAt();
        Instant horizonEnd = eligible.plus(horizon);
        boolean mature = !horizonEnd.isAfter(params.asOf());

        List<Instant> contacts = f.dispatchTimes().stream().filter(t -> !t.isAfter(params.asOf())).sorted().toList();
        Optional<Capture> firstCapture = f.captures().stream()
                .filter(c -> !c.at().isAfter(params.asOf()) && !c.at().isAfter(horizonEnd) && !c.at().isBefore(eligible))
                .min(Comparator.comparing(Capture::at).thenComparing(c -> c.source().name()));

        CampaignFacts.Assignment a = f.assignment();
        Instant m4Start = earliest(params.m4Start(), f.merchantM4Start());
        boolean postM4 = m4Start != null && !eligible.isBefore(m4Start);
        String captureSource = firstCapture.map(c -> c.source().name()).orElse(null);
        boolean synthetic = params.synthetic() || f.syntheticSource()
                || firstCapture.map(c -> c.source() == CaptureSource.SYNTHETIC).orElse(false);

        return new SnapshotRow(
                SnapshotRow.SCHEMA_VERSION,
                ids.merchantRef(f.merchantId()),
                ids.ref(f.merchantId(), "campaign", f.campaignId().toString()),
                ids.ref(f.merchantId(), "invoice", f.invoiceKey() != null ? f.invoiceKey() : "cmp:" + f.campaignId()),
                ids.ref(f.merchantId(), "customer", f.customerKey()),
                eligible.atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                a == null ? SnapshotRow.UNASSIGNED : a.arm(),
                a == null ? null : a.holdoutRateBp(),
                a == null ? null : a.propensityBp(),
                a == null ? null : a.source(),
                a == null ? null : a.saltVersion(),
                a == null ? null : a.hashVersion(),
                postM4,
                f.failureClass(),
                f.taxonomyVersion(),
                f.strategy(),
                f.compliance(),
                f.complianceReason(),
                !contacts.isEmpty(),
                contacts.size(),
                contacts.isEmpty() ? null : Duration.between(eligible, contacts.get(0)).toSeconds(),
                mature,
                firstCapture.isPresent(),
                firstCapture.map(c -> Duration.between(eligible, c.at()).toSeconds()).orElse(null),
                captureSource,
                f.recoveryAttribution(),
                f.amountMinor(),
                f.amountMinor() == null ? null : f.currency(),
                f.amountMinor() == null ? "UNKNOWN" : "KNOWN",
                f.stateAsOf(),
                f.legacyDecisionBlocks(),
                invoice.firstCampaign().equals(f.campaignId()),
                invoice.campaigns(),
                invoice.mature(),
                invoice.firstCapture() != null,
                invoice.firstCapture() == null ? null : Duration.between(invoice.firstEligible(), invoice.firstCapture().at()).toSeconds(),
                invoice.firstCapture() == null ? null : invoice.firstCapture().source().name(),
                invoice.contacts(),
                (synthetic || invoice.firstCapture() != null && invoice.firstCapture().source() == CaptureSource.SYNTHETIC
                        ? EvidenceLabel.SYNTHETIC : EvidenceLabel.MEASURED).name());
    }
}
