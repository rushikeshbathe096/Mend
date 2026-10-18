package com.mend.evaluation.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.3: the validator catches each defect class and never repairs anything. */
class SnapshotValidatorTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private static String validLine() {
        CampaignFacts f = new CampaignFacts(MERCHANT, UUID.randomUUID(), "inv:inv_v", null, T0,
                new CampaignFacts.Assignment("TREATMENT", 2000, 8000, "HASH", 1, "h"), "INSUFFICIENT_FUNDS", "t", "RETRY_LATER",
                "ALLOWED", "ALLOWED", List.of(), List.of(), null, "ELIGIBLE", 49_900L, "INR", 0, null, false);
        SnapshotParams params = new SnapshotParams(T0, T0.plusSeconds(86_400), T0.plusSeconds(30 * 86_400L), 14, T0, "razorpay-test", List.of());
        return SnapshotCodec.campaignsJsonl(SnapshotBuilder.build(List.of(f), List.of(), new Pseudonyms(Map.of(MERCHANT, new byte[32])), params).campaigns());
    }

    private static List<String> codes(String jsonl) {
        return SnapshotValidator.validate(jsonl).stream().map(SnapshotValidator.Defect::code).toList();
    }

    @Test
    @DisplayName("A well-formed row has no defect")
    void validRow() {
        assertEquals(List.of(), codes(validLine()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "AMOUNT_NEGATIVE           | \"amount_minor\":49900 | \"amount_minor\":-5",
            "AMOUNT_FRACTIONAL         | \"amount_minor\":49900 | \"amount_minor\":499.5",
            "AMOUNT_ZERO               | \"amount_minor\":49900 | \"amount_minor\":0",
            "AMOUNT_NOT_NUMBER         | \"amount_minor\":49900 | \"amount_minor\":\"49900\"",
            "AMOUNT_STATUS_INCONSISTENT| \"amount_status\":\"KNOWN\" | \"amount_status\":\"UNKNOWN\"",
            "ASSIGNMENT_MISSING        | \"arm\":\"TREATMENT\" | \"arm\":\"UNASSIGNED\"",
            "SCHEMA_BAD_ENUM           | \"arm\":\"TREATMENT\" | \"arm\":\"TREATED\"",
            "SCHEMA_VERSION            | eval-snapshot-v2 | eval-snapshot-v0",
            "SCHEMA_BAD_DAY            | \"eligible_day\":\"2026-09-01\" | \"eligible_day\":\"2026-09-01T00:00:00Z\"",
            "SCHEMA_BAD_TYPE           | \"mature\":true | \"mature\":\"yes\"",
            "SCHEMA_MISSING_FIELD      | \"currency\":\"INR\", | ''"
    })
    @DisplayName("Each defect is detected")
    void defects(String code, String from, String to) {
        String line = validLine();
        assertTrue(line.contains(from), from);
        assertTrue(codes(line.replace(from, to)).contains(code.trim()), codes(line.replace(from, to)).toString());
    }

    @Test
    @DisplayName("U4.3 review: RECOVERED state without attribution (outside the horizon), capture source missing, capture past the horizon, "
            + "TREATMENT propensity, currency, decision-file defects")
    void reviewDefects() {
        String line = validLine();
        assertTrue(codes(line.replace("\"state_as_of\":\"ELIGIBLE\"", "\"state_as_of\":\"RECOVERED\"")).contains("RECOVERED_WITHOUT_ATTRIBUTION"));
        String noSource = line.replace("\"recovered\":false", "\"recovered\":true").replace("\"capture_offset_s\":null", "\"capture_offset_s\":60");
        assertTrue(codes(noSource).contains("SCHEMA_INCONSISTENT"));
        String late = noSource.replace("\"capture_source\":null", "\"capture_source\":\"MEND\"").replace("\"capture_offset_s\":60", "\"capture_offset_s\":2000000");
        assertTrue(SnapshotValidator.validate(late, "", 14).stream().anyMatch(d -> d.code().equals("CAPTURE_OUTSIDE_HORIZON")));
        assertTrue(codes(line.replace("\"propensity_bp\":8000", "\"propensity_bp\":9000")).contains("PROPENSITY_INCONSISTENT"));
        assertTrue(codes(line.replace("\"currency\":\"INR\"", "\"currency\":\"rupee\"")).contains("AMOUNT_BAD_CURRENCY"));

        String decision = "{\"schema_version\":\"eval-snapshot-v2\",\"merchant_ref\":\"x\",\"campaign_ref\":\"%s\",\"intent_ref\":null,"
                + "\"decision_point\":\"%s\",\"chosen\":\"A\",\"reason_code\":null,\"alternatives\":[%s],\"propensity_bp\":%d,"
                + "\"taxonomy_version\":null,\"strategy_version\":null,\"compliance_version\":null,\"assignment_version\":null,\"offset_s\":0}\n";
        String ref = line.substring(line.indexOf("\"campaign_ref\":\"") + 16, line.indexOf("\"campaign_ref\":\"") + 48);
        assertEquals(List.of(), SnapshotValidator.validate(line, String.format(decision, ref, "STRATEGY", "\"A\"", 10000), 14));
        java.util.function.Function<String, List<String>> d = text -> SnapshotValidator.validate(line, text, 14).stream()
                .map(SnapshotValidator.Defect::code).toList();
        assertTrue(d.apply(String.format(decision, "0".repeat(32), "STRATEGY", "\"A\"", 10000)).contains("DECISION_ORPHAN"));
        assertTrue(d.apply(String.format(decision, ref, "STRATEGY", "\"A\"", 10001)).contains("DECISION_BAD_PROPENSITY"));
        assertTrue(d.apply(String.format(decision, ref, "STRATEGY", "\"B\"", 10000)).contains("DECISION_CHOSEN_NOT_ALTERNATIVE"));
        assertTrue(d.apply(String.format(decision, ref, "GUESS", "\"A\"", 10000)).contains("DECISION_BAD_POINT"));
    }

    @Test
    @DisplayName("RECOVERED without attribution, CONTROL contacted, duplicate rows and unparsable lines are detected")
    void semanticDefects() {
        String line = validLine();
        String recoveredUnknown = line.replace("\"recovered\":false", "\"recovered\":true")
                .replace("\"capture_offset_s\":null", "\"capture_offset_s\":60").replace("\"capture_source\":null", "\"capture_source\":\"UNKNOWN\"");
        assertTrue(codes(recoveredUnknown).contains("RECOVERED_WITHOUT_ATTRIBUTION"));
        String controlContacted = line.replace("\"arm\":\"TREATMENT\"", "\"arm\":\"CONTROL\"").replace("\"propensity_bp\":8000", "\"propensity_bp\":2000")
                .replace("\"contacted\":false", "\"contacted\":true");
        assertEquals(List.of("CONTROL_CONTACTED"), codes(controlContacted));
        assertTrue(codes(line + line).contains("DUPLICATE_ROW"));
        assertEquals(List.of("SCHEMA_UNPARSABLE"), codes("{not json\n"));
        assertTrue(codes(line.replace("\"recovered\":false", "\"recovered\":true")).contains("SCHEMA_INCONSISTENT"));
    }
}
