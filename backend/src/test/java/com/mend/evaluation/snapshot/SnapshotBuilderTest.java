package com.mend.evaluation.snapshot;

import com.mend.evaluation.snapshot.CampaignFacts.Assignment;
import com.mend.evaluation.snapshot.CampaignFacts.Capture;
import com.mend.evaluation.snapshot.CampaignFacts.CaptureSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.3: the pure snapshot builder: deterministic checksum, PII-free output, outcome within the horizon, point in time. */
class SnapshotBuilderTest {

    private static final UUID MERCHANT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant T0 = Instant.parse("2026-09-01T10:15:30Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");
    private static final SnapshotParams PARAMS = new SnapshotParams(Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-10T00:00:00Z"), AS_OF, 14, Instant.parse("2026-08-01T00:00:00Z"), "razorpay-live", List.of());

    private static Pseudonyms ids() {
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 9);
        return new Pseudonyms(Map.of(MERCHANT, salt));
    }

    private static CampaignFacts campaign(String suffix, String arm, Instant eligibleAt, List<Instant> contacts, List<Capture> captures,
                                          Long amount) {
        UUID id = UUID.nameUUIDFromBytes(suffix.getBytes());
        Assignment a = arm == null ? null : new Assignment(arm, 2000, "CONTROL".equals(arm) ? 2000 : 8000, "HASH", 1, "hmac-v1");
        return new CampaignFacts(MERCHANT, id, "inv:inv_" + suffix, "cust_" + suffix, eligibleAt, a, "INSUFFICIENT_FUNDS",
                "razorpay-error-taxonomy-v1", "RETRY_LATER", "ALLOWED", "ALLOWED", contacts, captures, null, "ELIGIBLE",
                amount, amount == null ? null : "INR", 0, null, false);
    }

    private static List<CampaignFacts> population() {
        return List.of(
                campaign("a", "TREATMENT", T0, List.of(T0.plusSeconds(60)), List.of(new Capture(T0.plus(Duration.ofDays(2)), CaptureSource.MEND)), 49_900L),
                campaign("b", "CONTROL", T0.plusSeconds(5), List.of(), List.of(new Capture(T0.plus(Duration.ofDays(20)), CaptureSource.ORIGINAL)), 10_000L),
                campaign("c", "CONTROL", T0.plusSeconds(9), List.of(), List.of(), null),
                campaign("d", null, Instant.parse("2026-07-01T00:00:00Z"), List.of(), List.of(), 500L));
    }

    @Test
    @DisplayName("Same facts in any order give the same files and sha256")
    void checksumIsStable() {
        List<CampaignFacts> facts = new ArrayList<>(population());
        SnapshotBuilder.Content a = SnapshotBuilder.build(facts, List.of(), ids(), PARAMS);
        Collections.reverse(facts);
        SnapshotBuilder.Content b = SnapshotBuilder.build(facts, List.of(), ids(), PARAMS);
        String shaA = SnapshotCodec.sha256(SnapshotCodec.campaignsJsonl(a.campaigns()), SnapshotCodec.decisionsJsonl(a.decisions()));
        String shaB = SnapshotCodec.sha256(SnapshotCodec.campaignsJsonl(b.campaigns()), SnapshotCodec.decisionsJsonl(b.decisions()));
        assertEquals(shaA, shaB);
        assertEquals(SnapshotCodec.campaignsCsv(a.campaigns()), SnapshotCodec.campaignsCsv(b.campaigns()));
        assertTrue(shaA.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("No raw id, invoice key, customer key or exact timestamp reaches the files")
    void piiFree() {
        List<CampaignFacts> facts = population();
        UUID intent = UUID.randomUUID();
        List<DecisionFacts> decisions = List.of(new DecisionFacts(MERCHANT, facts.get(0).campaignId(), intent, "DISPATCH", "DISPATCHED",
                null, List.of("BLOCKED", "DISPATCHED", "EXPIRED"), 10_000, "t", "s", "c", "a", T0.plusSeconds(60)));
        SnapshotBuilder.Content content = SnapshotBuilder.build(facts, decisions, ids(), PARAMS);
        String all = SnapshotCodec.campaignsJsonl(content.campaigns()) + SnapshotCodec.decisionsJsonl(content.decisions())
                + SnapshotCodec.campaignsCsv(content.campaigns());
        assertFalse(all.contains(MERCHANT.toString()));
        assertFalse(all.contains(intent.toString()));
        for (CampaignFacts f : facts) {
            assertFalse(all.contains(f.campaignId().toString()), "campaign id");
            assertFalse(all.contains(f.invoiceKey()), "invoice key");
            assertFalse(all.contains(f.customerKey()), "customer key");
        }
        assertFalse(all.contains("10:15"), "no time of day");
        assertFalse(all.contains("T10:"), "no ISO instants");
        assertEquals(1, content.decisions().size());
        assertEquals(60, content.decisions().get(0).offsetS());
    }

    @Test
    @DisplayName("Outcome = capture evidence within the horizon after first eligibility; later captures do not count")
    void horizon() {
        Map<String, SnapshotRow> rows = byInvoice(SnapshotBuilder.build(population(), List.of(), ids(), PARAMS).campaigns());
        SnapshotRow a = rows.get("a");
        assertTrue(a.mature());
        assertTrue(a.recovered());
        assertEquals("MEND", a.captureSource());
        assertEquals(2 * 86_400L, a.captureOffsetS());
        assertTrue(a.contacted());
        assertEquals(60L, a.firstContactOffsetS());
        assertEquals("2026-09-01", a.eligibleDay());
        SnapshotRow b = rows.get("b");
        assertFalse(b.recovered(), "captured on day 20, after the 14-day horizon");
        assertEquals("CONTROL", b.arm());
        assertEquals(2000, b.propensityBp());
        SnapshotRow c = rows.get("c");
        assertEquals("UNKNOWN", c.amountStatus());
        assertNull(c.amountMinor());
        SnapshotRow d = rows.get("d");
        assertEquals(SnapshotRow.UNASSIGNED, d.arm());
        assertFalse(d.postM4(), "eligible before the M4 start");
        assertEquals("MEASURED", a.evidenceLabel());
    }

    @Test
    @DisplayName("Point in time: contacts and captures after as_of are ignored; eligible less than H before as_of is immature")
    void pointInTime() {
        Instant late = AS_OF.minus(Duration.ofDays(3));
        CampaignFacts f = campaign("e", "TREATMENT", late, List.of(late.plusSeconds(10), AS_OF.plusSeconds(1)),
                List.of(new Capture(AS_OF.plusSeconds(5), CaptureSource.MEND)), 100L);
        SnapshotRow row = SnapshotBuilder.build(List.of(f), List.of(), ids(), PARAMS).campaigns().get(0);
        assertFalse(row.mature());
        assertFalse(row.recovered());
        assertEquals(1, row.contactCount());
    }

    @Test
    @DisplayName("DECISION U4.3-f: only razorpay-live is MEASURED; mock, test mode, unknown, demo sources and SYNTHETIC captures are SYNTHETIC")
    void syntheticLabels() {
        for (String mode : List.of("mock", "razorpay-test", "razorpay-unknown", "simulator")) {
            SnapshotParams p = new SnapshotParams(PARAMS.windowFrom(), PARAMS.windowTo(), AS_OF, 14, PARAMS.m4Start(), mode, List.of());
            assertTrue(SnapshotBuilder.build(population(), List.of(), ids(), p).campaigns().stream()
                    .allMatch(r -> r.evidenceLabel().equals("SYNTHETIC")), mode);
        }
        CampaignFacts demo = new CampaignFacts(MERCHANT, UUID.randomUUID(), "inv:inv_demo", null, T0, null, null, null, null, null, null,
                List.of(), List.of(), null, "ELIGIBLE", 100L, "INR", 0, null, true);
        assertEquals("SYNTHETIC", SnapshotBuilder.build(List.of(demo), List.of(), ids(), PARAMS).campaigns().get(0).evidenceLabel());
        CampaignFacts synth = campaign("s", "TREATMENT", T0, List.of(), List.of(new Capture(T0.plusSeconds(100), CaptureSource.SYNTHETIC)), 100L);
        assertEquals("SYNTHETIC", SnapshotBuilder.build(List.of(synth), List.of(), ids(), PARAMS).campaigns().get(0).evidenceLabel());
    }

    @Test
    @DisplayName("post-M4 starts at the earlier of the configured start and the merchant's first assignment")
    void postM4Start() {
        SnapshotParams noGlobal = new SnapshotParams(PARAMS.windowFrom(), PARAMS.windowTo(), AS_OF, 14, null, "razorpay-live", List.of());
        CampaignFacts before = new CampaignFacts(MERCHANT, UUID.randomUUID(), "inv:inv_p1", null, T0, null, null, null, null, null, null,
                List.of(), List.of(), null, "ELIGIBLE", null, null, 0, T0.plusSeconds(1), false);
        CampaignFacts after = new CampaignFacts(MERCHANT, UUID.randomUUID(), "inv:inv_p2", null, T0, null, null, null, null, null, null,
                List.of(), List.of(), null, "ELIGIBLE", null, null, 0, T0, false);
        CampaignFacts never = new CampaignFacts(MERCHANT, UUID.randomUUID(), "inv:inv_p3", null, T0, null, null, null, null, null, null,
                List.of(), List.of(), null, "ELIGIBLE", null, null, 0, null, false);
        List<SnapshotRow> rows = SnapshotBuilder.build(List.of(before, after, never), List.of(), ids(), noGlobal).campaigns();
        Pseudonyms ids = ids();
        assertFalse(rows.stream().filter(r -> r.invoiceRef().equals(ids.ref(MERCHANT, "invoice", "inv:inv_p1"))).findFirst().orElseThrow().postM4());
        assertTrue(rows.stream().filter(r -> r.invoiceRef().equals(ids.ref(MERCHANT, "invoice", "inv:inv_p2"))).findFirst().orElseThrow().postM4());
        assertFalse(rows.stream().filter(r -> r.invoiceRef().equals(ids.ref(MERCHANT, "invoice", "inv:inv_p3"))).findFirst().orElseThrow().postM4());
    }

    @Test
    @DisplayName("Checksum: moving a line from one file to the other changes it; parameters are truncated to microseconds")
    void checksumRule() {
        assertNotEquals(SnapshotCodec.snapshotSha256("a\nb\n", "c\n"), SnapshotCodec.snapshotSha256("a\n", "b\nc\n"));
        SnapshotParams p = new SnapshotParams(Instant.parse("2026-09-01T00:00:00.123456789Z"), PARAMS.windowTo(), AS_OF, 14, null, "mock", List.of());
        assertEquals(Instant.parse("2026-09-01T00:00:00.123456Z"), p.windowFrom());
    }

    @Test
    @DisplayName("Pseudonyms differ by merchant salt and by kind")
    void pseudonyms() {
        byte[] other = new byte[32];
        UUID otherMerchant = UUID.randomUUID();
        Pseudonyms p = new Pseudonyms(Map.of(MERCHANT, new byte[32], otherMerchant, other));
        assertNotEquals(p.ref(MERCHANT, "invoice", "x"), p.ref(MERCHANT, "campaign", "x"));
        assertEquals(p.ref(MERCHANT, "invoice", "x"), p.ref(otherMerchant, "invoice", "x"), "same salt, same ref");
        Pseudonyms q = new Pseudonyms(Map.of(MERCHANT, ids().ref(MERCHANT, "k", "v").substring(0, 32).getBytes()));
        assertNotEquals(p.ref(MERCHANT, "invoice", "x"), q.ref(MERCHANT, "invoice", "x"));
        assertThrows(IllegalStateException.class, () -> p.ref(UUID.randomUUID(), "invoice", "x"));
    }

    private static Map<String, SnapshotRow> byInvoice(List<SnapshotRow> rows) {
        Map<String, SnapshotRow> out = new HashMap<>();
        Pseudonyms ids = ids();
        for (String s : List.of("a", "b", "c", "d")) {
            String ref = ids.ref(MERCHANT, "invoice", "inv:inv_" + s);
            out.put(s, rows.stream().filter(r -> r.invoiceRef().equals(ref)).findFirst().orElseThrow());
        }
        return out;
    }
}
