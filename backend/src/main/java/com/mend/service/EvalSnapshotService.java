package com.mend.service;

import com.mend.domain.entity.EvalSnapshot;
import com.mend.domain.entity.MerchantExperimentSalt;
import com.mend.domain.repository.EvalSnapshotRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.evaluation.snapshot.CampaignFacts;
import com.mend.evaluation.snapshot.CampaignFacts.Capture;
import com.mend.evaluation.snapshot.CampaignFacts.CaptureSource;
import com.mend.evaluation.snapshot.DecisionFacts;
import com.mend.evaluation.snapshot.Pseudonyms;
import com.mend.evaluation.snapshot.SnapshotBuilder;
import com.mend.evaluation.snapshot.SnapshotCodec;
import com.mend.evaluation.snapshot.SnapshotParams;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.evaluation.snapshot.SnapshotValidator;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import com.mend.provider.MoneyMinor;
import com.mend.provider.razorpay.RazorpayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * M4 U4.3 (ADR-004 §5, AGENTS.md invariant 23): builds an evaluation snapshot as of a point in time from append-only,
 * timestamped sources only (audit state transitions and capture-evidence codes, holdout assignments, decision_log, the
 * failure webhook received by then), never from current state. Writes PII-free files and an immutable eval_snapshots row.
 * Validation failures are recorded (status INVALID), never repaired. Re-running the same window, as_of, horizon and filters
 * returns the existing snapshot when the content checksum is the same.
 */
@Service
public class EvalSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(EvalSnapshotService.class);
    private static final int CHUNK = 5_000;
    private static final Map<String, CaptureSource> CAPTURE_CODES = Map.of(
            "CAPTURED_ON_TERMINAL_CAMPAIGN", CaptureSource.MEND,
            "CAPTURED_EVIDENCE_NOT_APPLIED", CaptureSource.MEND,
            "ORIGINAL_PAYMENT_CAPTURED_ON_TERMINAL_CAMPAIGN", CaptureSource.ORIGINAL);

    public record Request(Instant windowFrom, Instant windowTo, Instant asOf, Integer horizonDays, List<UUID> merchantIds) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ExperimentAssignmentService experimentAssignmentService;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookAmountResolver amountResolver;
    private final EvalSnapshotRepository snapshotRepository;
    private final ObjectProvider<RazorpayProperties> razorpayProperties;
    private final String paymentProvider;
    private final Path snapshotDir;

    public EvalSnapshotService(NamedParameterJdbcTemplate jdbc,
                               ExperimentAssignmentService experimentAssignmentService,
                               WebhookEventRepository webhookEventRepository,
                               WebhookAmountResolver amountResolver,
                               EvalSnapshotRepository snapshotRepository,
                               ObjectProvider<RazorpayProperties> razorpayProperties,
                               @Value("${mend.payment.provider:mock}") String paymentProvider,
                               @Value("${mend.eval.snapshot.dir:${java.io.tmpdir}/mend-eval-snapshots}") String snapshotDir) {
        this.jdbc = jdbc;
        this.experimentAssignmentService = experimentAssignmentService;
        this.webhookEventRepository = webhookEventRepository;
        this.amountResolver = amountResolver;
        this.snapshotRepository = snapshotRepository;
        this.razorpayProperties = razorpayProperties;
        this.paymentProvider = paymentProvider;
        this.snapshotDir = Path.of(snapshotDir);
    }

    public Path snapshotDir() {
        return snapshotDir;
    }

    @Transactional
    public EvalSnapshot createSnapshot(Request request) {
        Instant now = Instant.now();
        Instant asOf = request.asOf() != null ? request.asOf() : request.windowTo();
        if (asOf == null || asOf.isAfter(now)) {
            throw new IllegalArgumentException("as_of must not be in the future (a snapshot is a point in time)");
        }
        SnapshotParams params = new SnapshotParams(request.windowFrom(), request.windowTo(), asOf,
                request.horizonDays() != null ? request.horizonDays() : SnapshotParams.DEFAULT_HORIZON_DAYS,
                m4Start(), providerMode(), request.merchantIds());

        List<CampaignFacts> facts = new ArrayList<>();
        List<DecisionFacts> decisions = new ArrayList<>();
        load(params, facts, decisions);

        Map<UUID, byte[]> salts = new HashMap<>();
        List<UUID> merchants = new ArrayList<>(params.merchantFilter());
        facts.forEach(f -> merchants.add(f.merchantId()));
        for (UUID merchant : merchants) {
            salts.computeIfAbsent(merchant, m -> experimentAssignmentService.salt(m, MerchantExperimentSalt.EVAL_ID).getSalt());
        }
        Pseudonyms ids = new Pseudonyms(salts);
        SnapshotBuilder.Content content = SnapshotBuilder.build(facts, decisions, ids, params);
        String campaigns = SnapshotCodec.campaignsJsonl(content.campaigns());
        String decisionLines = SnapshotCodec.decisionsJsonl(content.decisions());
        String sha = SnapshotCodec.sha256(campaigns, decisionLines);
        Map<String, Object> filters = Map.of("merchant_ids", params.merchantFilter().stream().map(UUID::toString).toList());

        Optional<EvalSnapshot> same = snapshotRepository.findByWindowFromAndWindowToAndAsOfAndSha256(
                        params.windowFrom(), params.windowTo(), params.asOf(), sha).stream()
                .filter(s -> s.getHorizonDays() == params.horizonDays() && s.getProviderMode().equals(params.providerMode())
                        && Objects.equals(s.getFilters(), filters))
                .findFirst();
        if (same.isPresent()) {
            log.info("event=eval_snapshot_reused id={} sha256={}", same.get().getId(), sha);
            return same.get();
        }

        List<Map<String, String>> errors = SnapshotValidator.validate(campaigns).stream()
                .map(d -> {
                    Map<String, String> e = new LinkedHashMap<>();
                    e.put("code", d.code());
                    e.put("campaign_ref", d.campaignRef());
                    e.put("detail", d.detail());
                    return e;
                }).toList();
        UUID id = UUID.randomUUID();
        EvalSnapshot snapshot = new EvalSnapshot(id, SnapshotRow.SCHEMA_VERSION, now, params.windowFrom(), params.windowTo(),
                params.asOf(), params.horizonDays(), filters, params.providerMode(), content.campaigns().size(),
                content.decisions().size(), sha, errors.isEmpty() ? EvalSnapshot.Status.VALID : EvalSnapshot.Status.INVALID, errors);
        writeFiles(snapshot, campaigns, decisionLines, SnapshotCodec.campaignsCsv(content.campaigns()), ids, params);
        snapshot = snapshotRepository.saveAndFlush(snapshot);
        log.info("event=eval_snapshot_created id={} rows={} decisions={} status={} sha256={}", id, snapshot.getRowCount(),
                snapshot.getDecisionCount(), snapshot.getStatus(), sha);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<EvalSnapshot> recent() {
        return snapshotRepository.findTop50ByOrderByCreatedAtDesc();
    }

    /** mock | razorpay-test | razorpay-live | razorpay-unknown, from configuration only (the key itself is never read out). */
    String providerMode() {
        if (!"razorpay".equalsIgnoreCase(paymentProvider)) {
            return "mock";
        }
        RazorpayProperties props = razorpayProperties.getIfAvailable();
        String keyId = props != null ? props.keyId() : null;
        if (keyId == null) {
            return "razorpay-unknown";
        }
        return keyId.startsWith("rzp_test_") ? "razorpay-test" : keyId.startsWith("rzp_live_") ? "razorpay-live" : "razorpay-unknown";
    }

    /** First eligibility at or after V22's installation is post-M4 (an assignment is required). */
    private Instant m4Start() {
        List<LocalDateTime> installed = jdbc.query("SELECT installed_on FROM flyway_schema_history WHERE version = '22' AND success",
                Map.of(), (rs, i) -> rs.getObject(1, LocalDateTime.class));
        return installed.isEmpty() ? null : installed.get(0).toInstant(ZoneOffset.UTC);
    }

    private void load(SnapshotParams params, List<CampaignFacts> facts, List<DecisionFacts> decisions) {
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("from", utc(params.windowFrom()))
                .addValue("to", utc(params.windowTo()))
                .addValue("asOf", utc(params.asOf()));
        String merchantClause = "";
        if (!params.merchantFilter().isEmpty()) {
            merchantClause = " AND c.merchant_id IN (:merchants)";
            args.addValue("merchants", params.merchantFilter());
        }
        record Base(UUID id, UUID merchantId, String invoiceKey, String customerKey, String paymentId, String attribution, Instant eligibleAt) {
        }
        List<Base> base = jdbc.query("""
                SELECT c.id, c.merchant_id, c.invoice_key, c.customer_id_hash, c.payment_id, c.recovery_attribution, e.first_eligible
                FROM campaigns c
                JOIN (SELECT campaign_id, min(created_at) AS first_eligible FROM audit_logs
                      WHERE event_type = 'CAMPAIGN_STATE_TRANSITION' AND metadata->>'newState' = 'ELIGIBLE' AND campaign_id IS NOT NULL
                      GROUP BY campaign_id) e ON e.campaign_id = c.id
                WHERE e.first_eligible >= :from AND e.first_eligible < :to AND e.first_eligible <= :asOf""" + merchantClause
                        + " ORDER BY c.id", args,
                (rs, i) -> new Base(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, LocalDateTime.class).toInstant(ZoneOffset.UTC)));

        for (int start = 0; start < base.size(); start += CHUNK) {
            List<Base> chunk = base.subList(start, Math.min(base.size(), start + CHUNK));
            List<UUID> ids = chunk.stream().map(Base::id).toList();
            MapSqlParameterSource q = new MapSqlParameterSource()
                    .addValue("ids", ids)
                    .addValue("asOf", utc(params.asOf()))
                    .addValue("asOfTz", params.asOf().atOffset(ZoneOffset.UTC));

            Map<UUID, CampaignFacts.Assignment> assignments = new HashMap<>();
            jdbc.query("""
                    SELECT campaign_id, arm, holdout_rate_bp, source, salt_version, hash_version FROM holdout_assignments
                    WHERE campaign_id IN (:ids) AND assigned_at <= :asOfTz""", q, rs -> {
                int rate = rs.getInt(3);
                assignments.put(rs.getObject(1, UUID.class), new CampaignFacts.Assignment(rs.getString(2), rate,
                        HoldoutAssigner.propensityBp(Arm.valueOf(rs.getString(2)), rate), rs.getString(4), rs.getInt(5), rs.getString(6)));
            });

            Map<UUID, String[]> classes = new HashMap<>();
            jdbc.query("""
                    SELECT DISTINCT ON (campaign_id) campaign_id, failure_class, taxonomy_version FROM classification_results
                    WHERE campaign_id IN (:ids) AND created_at <= :asOf ORDER BY campaign_id, created_at DESC, id""", q,
                    rs -> { classes.put(rs.getObject(1, UUID.class), new String[]{rs.getString(2), rs.getString(3)}); });

            Map<UUID, List<DecisionFacts>> decisionsByCampaign = new HashMap<>();
            jdbc.query("""
                    SELECT merchant_id, campaign_id, intent_id, decision_point, chosen, reason_code, alternatives::text, propensity_bp,
                           taxonomy_version, strategy_version, compliance_version, assignment_version, decided_at
                    FROM decision_log WHERE campaign_id IN (:ids) AND decided_at <= :asOfTz ORDER BY decided_at, id""", q, rs -> {
                DecisionFacts d = new DecisionFacts(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), stringList(rs.getString(7)), rs.getInt(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class).toInstant());
                decisionsByCampaign.computeIfAbsent(d.campaignId(), k -> new ArrayList<>()).add(d);
                decisions.add(d);
            });

            // Intents dispatched before the decision log existed (pre-U4.2) count from their dispatched_at.
            Map<UUID, List<Instant>> legacyDispatches = new HashMap<>();
            jdbc.query("""
                    SELECT i.campaign_id, i.dispatched_at FROM action_intents i
                    WHERE i.campaign_id IN (:ids) AND i.dispatched_at IS NOT NULL AND i.dispatched_at <= :asOfTz
                      AND NOT EXISTS (SELECT 1 FROM decision_log d WHERE d.intent_id = i.id AND d.decision_point = 'DISPATCH')""", q,
                    rs -> { legacyDispatches.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(rs.getObject(2, OffsetDateTime.class).toInstant()); });

            Map<UUID, List<Object[]>> audit = new HashMap<>();
            q.addValue("codes", List.copyOf(CAPTURE_CODES.keySet()));
            jdbc.query("""
                    SELECT campaign_id, event_type, metadata->>'newState', created_at FROM audit_logs
                    WHERE campaign_id IN (:ids) AND created_at <= :asOf
                      AND (event_type = 'CAMPAIGN_STATE_TRANSITION' OR event_type IN (:codes))
                    ORDER BY created_at, id""", q, rs -> {
                audit.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new Object[]{
                        rs.getString(2), rs.getString(3), rs.getObject(4, LocalDateTime.class).toInstant(ZoneOffset.UTC)});
            });

            for (Base b : chunk) {
                facts.add(facts(b.id(), b.merchantId(), b.invoiceKey(), b.customerKey(), b.paymentId(), b.attribution(), b.eligibleAt(),
                        assignments.get(b.id()), classes.get(b.id()), decisionsByCampaign.getOrDefault(b.id(), List.of()),
                        legacyDispatches.getOrDefault(b.id(), List.of()), audit.getOrDefault(b.id(), List.of()), params.asOf()));
            }
        }
    }

    private CampaignFacts facts(UUID id, UUID merchantId, String invoiceKey, String customerKey, String paymentId, String attribution,
                                Instant eligibleAt, CampaignFacts.Assignment assignment, String[] classification,
                                List<DecisionFacts> decisions, List<Instant> legacyDispatches, List<Object[]> audit, Instant asOf) {
        String state = null;
        Instant recoveredAt = null;
        List<Capture> captures = new ArrayList<>();
        for (Object[] row : audit) {
            String type = (String) row[0];
            Instant at = (Instant) row[2];
            if ("CAMPAIGN_STATE_TRANSITION".equals(type)) {
                state = (String) row[1];
                if ("RECOVERED".equals(row[1]) && recoveredAt == null) {
                    recoveredAt = at;
                }
            } else {
                captures.add(new Capture(at, CAPTURE_CODES.get(type)));
            }
        }
        // The attribution is written in the same save as RECOVERED (terminal), so it is known as of as_of iff RECOVERED is.
        String attributionAsOf = recoveredAt != null ? attribution : null;
        if (recoveredAt != null) {
            captures.add(new Capture(recoveredAt, attributionAsOf == null ? CaptureSource.UNKNOWN : switch (attributionAsOf) {
                case "MEND" -> CaptureSource.MEND;
                case "ORIGINAL_PAYMENT_LATE_CAPTURE" -> CaptureSource.ORIGINAL;
                case "SYNTHETIC" -> CaptureSource.SYNTHETIC;
                default -> CaptureSource.UNKNOWN;
            }));
        }

        List<Instant> contacts = new ArrayList<>(legacyDispatches);
        String strategy = null;
        String compliance = null;
        String complianceReason = null;
        int legacyBlocks = 0;
        for (DecisionFacts d : decisions) {
            switch (d.decisionPoint()) {
                case "STRATEGY" -> strategy = strategy == null ? d.chosen() : strategy;
                case "COMPLIANCE" -> {
                    if (compliance == null) {
                        compliance = d.chosen();
                        complianceReason = d.reasonCode();
                    }
                }
                case "DISPATCH" -> {
                    if (DecisionLogService.DISPATCHED.equals(d.chosen())) {
                        contacts.add(d.decidedAt());
                    } else if ("LEGACY_DECISION".equals(d.reasonCode())) {
                        legacyBlocks++;
                    }
                }
                default -> { }
            }
        }

        Long amount = null;
        String currency = null;
        if (paymentId != null) {
            Optional<MoneyMinor> money = webhookEventRepository.findByMerchantIdAndExternalEventId(merchantId, paymentId)
                    .filter(e -> e.getReceivedAt() == null || !e.getReceivedAt().isAfter(asOf))
                    .flatMap(amountResolver::extractMoneyMinor);
            if (money.isPresent()) {
                amount = money.get().amountMinor();
                currency = money.get().currency();
            }
        }
        return new CampaignFacts(merchantId, id, invoiceKey, customerKey, eligibleAt, assignment,
                classification != null ? classification[0] : null, classification != null ? classification[1] : null,
                strategy, compliance, complianceReason, contacts, captures, attributionAsOf, state, amount, currency, legacyBlocks);
    }

    private void writeFiles(EvalSnapshot s, String campaigns, String decisions, String csv, Pseudonyms ids, SnapshotParams params) {
        Path dir = snapshotDir.resolve(s.getId().toString());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("campaigns.jsonl"), campaigns, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("decisions.jsonl"), decisions, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("campaigns.csv"), csv, StandardCharsets.UTF_8);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("snapshot_id", s.getId().toString());
            manifest.put("schema_version", s.getSchemaVersion());
            manifest.put("created_at", s.getCreatedAt().toString());
            manifest.put("window_from", s.getWindowFrom().toString());
            manifest.put("window_to", s.getWindowTo().toString());
            manifest.put("as_of", s.getAsOf().toString());
            manifest.put("horizon_days", s.getHorizonDays());
            manifest.put("provider_mode", s.getProviderMode());
            manifest.put("merchant_refs", params.merchantFilter().stream()
                    .map(m -> ids.ref(m, "merchant", "self") ).toList());
            manifest.put("row_count", s.getRowCount());
            manifest.put("decision_count", s.getDecisionCount());
            manifest.put("sha256", s.getSha256());
            manifest.put("sha256_covers", List.of("campaigns.jsonl", "decisions.jsonl"));
            manifest.put("status", s.getStatus().name());
            manifest.put("errors", s.getErrors());
            Files.writeString(dir.resolve("manifest.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write snapshot files to " + dir, e);
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static List<String> stringList(String json) {
        List<String> out = new ArrayList<>();
        JsonMapper.builder().build().readTree(json).forEach(n -> out.add(n.stringValue()));
        return out;
    }
}
