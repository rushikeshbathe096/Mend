package com.mend.service;

import com.mend.domain.entity.EvalSnapshot;
import com.mend.domain.entity.MerchantExperimentSalt;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.EvalSnapshotRepository;
import com.mend.evaluation.snapshot.CampaignFacts;
import com.mend.evaluation.snapshot.CampaignFacts.Capture;
import com.mend.evaluation.snapshot.CampaignFacts.CaptureSource;
import com.mend.evaluation.snapshot.DecisionFacts;
import com.mend.evaluation.snapshot.Pseudonyms;
import com.mend.evaluation.snapshot.SnapshotBuilder;
import com.mend.evaluation.snapshot.SnapshotParams;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.evaluation.snapshot.SnapshotWriter;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import com.mend.evaluation.snapshot.SnapshotCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
    /** Webhooks the demo fabricates ({@code DemoController.DEMO_SOURCE}); their rows are SYNTHETIC. */
    static final String DEMO_SOURCE = "MEND_DEMO_SIMULATED";
    private static final Map<String, CaptureSource> CAPTURE_CODES = Map.of(
            "CAPTURED_ON_TERMINAL_CAMPAIGN", CaptureSource.MEND,
            "CAPTURED_EVIDENCE_NOT_APPLIED", CaptureSource.MEND,
            "ORIGINAL_PAYMENT_CAPTURED_ON_TERMINAL_CAMPAIGN", CaptureSource.ORIGINAL);

    public record Request(Instant windowFrom, Instant windowTo, Instant asOf, Integer horizonDays, List<UUID> merchantIds) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ExperimentAssignmentService experimentAssignmentService;
    private final WebhookAmountResolver amountResolver;
    private final EvalSnapshotRepository snapshotRepository;
    private final ObjectProvider<RazorpayProperties> razorpayProperties;
    private final String paymentProvider;
    private final Path snapshotDir;
    private final Duration settleMargin;
    private final Instant configuredM4Start;
    private final com.mend.domain.repository.MerchantRepository merchantRepository;
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

    public EvalSnapshotService(NamedParameterJdbcTemplate jdbc,
                               ExperimentAssignmentService experimentAssignmentService,
                               WebhookAmountResolver amountResolver,
                               EvalSnapshotRepository snapshotRepository,
                               ObjectProvider<RazorpayProperties> razorpayProperties,
                               @Value("${mend.payment.provider:mock}") String paymentProvider,
                               @Value("${mend.eval.snapshot.dir:${java.io.tmpdir}/mend-eval-snapshots}") String snapshotDir,
                               @Value("${mend.eval.snapshot.settle-minutes:15}") long settleMinutes,
                               @Value("${mend.eval.m4-start:}") String m4Start,
                               com.mend.domain.repository.MerchantRepository merchantRepository) {
        this.settleMargin = Duration.ofMinutes(settleMinutes);
        this.configuredM4Start = m4Start == null || m4Start.isBlank() ? null : Instant.parse(m4Start);
        this.merchantRepository = merchantRepository;
        this.jdbc = jdbc;
        this.experimentAssignmentService = experimentAssignmentService;
        this.amountResolver = amountResolver;
        this.snapshotRepository = snapshotRepository;
        this.razorpayProperties = razorpayProperties;
        this.paymentProvider = paymentProvider;
        this.snapshotDir = Path.of(snapshotDir);
    }

    public Path snapshotDir() {
        return snapshotDir;
    }

    /**
     * REPEATABLE READ: every query of one snapshot sees the same database state. as_of must be at least the settle margin in
     * the past, so rows timestamped before as_of but committed late cannot appear on a re-run (U4.3 review).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public EvalSnapshot createSnapshot(Request request) {
        Instant now = Instant.now();
        Instant asOf = request.asOf() != null ? request.asOf() : request.windowTo();
        requireSettled(asOf, now, settleMargin);
        for (UUID merchant : request.merchantIds() == null ? List.<UUID>of() : request.merchantIds()) {
            if (!merchantRepository.existsById(merchant)) {
                throw new IllegalArgumentException("unknown merchant in the filter: " + merchant);
            }
        }
        SnapshotParams params = new SnapshotParams(request.windowFrom(), request.windowTo(), asOf,
                request.horizonDays() != null ? request.horizonDays() : SnapshotParams.DEFAULT_HORIZON_DAYS,
                configuredM4Start, providerMode(), request.merchantIds());

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
        String sha = SnapshotWriter.prepare(null, content, params.horizonDays()).sha256();
        Map<String, Object> filters = Map.of("merchant_ids", params.merchantFilter().stream().map(UUID::toString).toList());
        List<String> merchantRefs = params.merchantFilter().stream().map(ids::merchantRef).toList();

        Optional<EvalSnapshot> same = sameSnapshot(params, sha, filters);
        if (same.isPresent()) {
            ensureFiles(same.get(), params, content, merchantRefs);
            log.info("event=eval_snapshot_reused id={} sha256={}", same.get().getId(), sha);
            return same.get();
        }

        UUID id = UUID.randomUUID();
        // Files go to a temporary directory and are published (atomic move) only after the row commits.
        Path temp = snapshotDir.resolve(".tmp-" + id);
        SnapshotWriter.Written written = SnapshotWriter.write(temp, id.toString(), now, params, content, merchantRefs);
        List<Map<String, String>> errors = written.defects().stream()
                .map(d -> {
                    Map<String, String> e = new LinkedHashMap<>();
                    e.put("code", d.code());
                    e.put("campaign_ref", d.campaignRef());
                    e.put("detail", d.detail());
                    return e;
                }).toList();
        EvalSnapshot.Status status = errors.isEmpty() ? EvalSnapshot.Status.VALID : EvalSnapshot.Status.INVALID;
        int inserted = jdbc.update("""
                INSERT INTO eval_snapshots (id, schema_version, created_at, window_from, window_to, as_of, horizon_days, filters,
                    provider_mode, row_count, decision_count, sha256, status, errors)
                VALUES (:id, :schema, :createdAt, :from, :to, :asOf, :horizon, CAST(:filters AS jsonb), :mode, :rows, :decisions,
                    :sha, :status, CAST(:errors AS jsonb))
                ON CONFLICT DO NOTHING""", new MapSqlParameterSource()
                .addValue("id", id).addValue("schema", SnapshotRow.SCHEMA_VERSION).addValue("createdAt", now.atOffset(ZoneOffset.UTC))
                .addValue("from", params.windowFrom().atOffset(ZoneOffset.UTC)).addValue("to", params.windowTo().atOffset(ZoneOffset.UTC))
                .addValue("asOf", params.asOf().atOffset(ZoneOffset.UTC)).addValue("horizon", params.horizonDays())
                .addValue("filters", JSON.writeValueAsString(filters)).addValue("mode", params.providerMode())
                .addValue("rows", content.campaigns().size()).addValue("decisions", content.decisions().size())
                .addValue("sha", sha).addValue("status", status.name()).addValue("errors", JSON.writeValueAsString(errors)));
        if (inserted == 0) {
            // a concurrent run stored the same snapshot first (V25 unique index): use it
            deleteQuietly(temp);
            EvalSnapshot existing = sameSnapshot(params, sha, filters)
                    .orElseThrow(() -> new IllegalStateException("snapshot insert conflicted but no matching snapshot exists"));
            ensureFiles(existing, params, content, merchantRefs);
            return existing;
        }
        publishAfterCommit(temp, snapshotDir.resolve(id.toString()));
        log.info("event=eval_snapshot_created id={} rows={} decisions={} status={} sha256={}", id, content.campaigns().size(),
                content.decisions().size(), status, sha);
        return snapshotRepository.findById(id).orElseThrow();
    }

    /** U4.3 review: as_of must be at least {@code margin} in the past (late commits settle first). */
    static void requireSettled(Instant asOf, Instant now, Duration margin) {
        if (asOf == null || asOf.isAfter(now.minus(margin))) {
            throw new IllegalArgumentException("as_of must be at least " + margin.toMinutes() + " minutes in the past (a snapshot is a "
                    + "settled point in time)");
        }
    }

    private Optional<EvalSnapshot> sameSnapshot(SnapshotParams params, String sha, Map<String, Object> filters) {
        return snapshotRepository.findByWindowFromAndWindowToAndAsOfAndSha256(params.windowFrom(), params.windowTo(), params.asOf(), sha)
                .stream()
                .filter(s -> s.getHorizonDays() == params.horizonDays() && s.getProviderMode().equals(params.providerMode())
                        && Objects.equals(s.getFilters(), filters))
                .findFirst();
    }

    /** A reused snapshot whose files are missing or no longer match its checksum gets them rewritten (same content). */
    private void ensureFiles(EvalSnapshot snapshot, SnapshotParams params, SnapshotBuilder.Content content, List<String> merchantRefs) {
        Path dir = snapshotDir.resolve(snapshot.getId().toString());
        try {
            if (Files.isRegularFile(dir.resolve("manifest.json")) && snapshot.getSha256().equals(SnapshotCodec.snapshotSha256(
                    Files.readString(dir.resolve("campaigns.jsonl")), Files.readString(dir.resolve("decisions.jsonl"))))) {
                return;
            }
        } catch (IOException unreadable) {
            log.warn("event=eval_snapshot_files_unreadable id={}", snapshot.getId());
        }
        Path temp = snapshotDir.resolve(".tmp-" + UUID.randomUUID());
        SnapshotWriter.write(temp, snapshot.getId().toString(), snapshot.getCreatedAt(), params, content, merchantRefs);
        deleteQuietly(dir);
        move(temp, dir);
        log.warn("event=eval_snapshot_files_rewritten id={}", snapshot.getId());
    }

    private void publishAfterCommit(Path temp, Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            move(temp, target);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    move(temp, target);
                } else {
                    deleteQuietly(temp);
                }
            }
        });
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not publish snapshot files to " + to, e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("event=eval_snapshot_cleanup_failed dir={}", dir.getFileName());
        }
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

        // Each merchant's M4 start: its first holdout assignment at or before as_of (append-only, point in time).
        Map<UUID, Instant> merchantM4Start = new HashMap<>();
        List<UUID> baseMerchants = base.stream().map(Base::merchantId).distinct().toList();
        if (!baseMerchants.isEmpty()) {
            jdbc.query("""
                    SELECT merchant_id, min(assigned_at) FROM holdout_assignments
                    WHERE merchant_id IN (:merchants) AND assigned_at <= :asOfTz GROUP BY merchant_id""",
                    new MapSqlParameterSource().addValue("merchants", baseMerchants).addValue("asOfTz", params.asOf().atOffset(ZoneOffset.UTC)),
                    rs -> { merchantM4Start.put(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class).toInstant()); });
        }

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
                      AND NOT EXISTS (SELECT 1 FROM decision_log d WHERE d.intent_id = i.id AND d.decision_point = 'DISPATCH'
                                      AND d.decided_at <= :asOfTz)""", q,
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

            // U4.3 review: the failure webhooks of the chunk in one query (received by as_of), not one entity load per campaign.
            Map<String, WebhookEvent> failures = new HashMap<>();
            List<String> paymentIds = chunk.stream().map(Base::paymentId).filter(Objects::nonNull).distinct().toList();
            if (!paymentIds.isEmpty()) {
                jdbc.query("""
                        SELECT merchant_id, external_event_id, event_type, source, raw_payload FROM webhook_events
                        WHERE external_event_id IN (:paymentIds) AND received_at <= :asOf""",
                        new MapSqlParameterSource().addValue("paymentIds", paymentIds).addValue("asOf", utc(params.asOf())), rs -> {
                            WebhookEvent e = new WebhookEvent(null, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
                            failures.put(rs.getObject(1, UUID.class) + "|" + rs.getString(2), e);
                        });
            }

            for (Base b : chunk) {
                WebhookEvent failure = b.paymentId() == null ? null : failures.get(b.merchantId() + "|" + b.paymentId());
                facts.add(facts(b.id(), b.merchantId(), b.invoiceKey(), b.customerKey(), b.attribution(), b.eligibleAt(),
                        assignments.get(b.id()), classes.get(b.id()), decisionsByCampaign.getOrDefault(b.id(), List.of()),
                        legacyDispatches.getOrDefault(b.id(), List.of()), audit.getOrDefault(b.id(), List.of()), failure,
                        merchantM4Start.get(b.merchantId())));
            }
        }
    }

    private CampaignFacts facts(UUID id, UUID merchantId, String invoiceKey, String customerKey, String attribution,
                                Instant eligibleAt, CampaignFacts.Assignment assignment, String[] classification,
                                List<DecisionFacts> decisions, List<Instant> legacyDispatches, List<Object[]> audit, WebhookEvent failure,
                                Instant merchantM4Start) {
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
        Optional<MoneyMinor> money = failure == null ? Optional.empty() : amountResolver.extractMoneyMinor(failure);
        if (money.isPresent()) {
            amount = money.get().amountMinor();
            currency = money.get().currency();
        }
        boolean syntheticSource = failure != null && DEMO_SOURCE.equals(failure.getSource());
        return new CampaignFacts(merchantId, id, invoiceKey, customerKey, eligibleAt, assignment,
                classification != null ? classification[0] : null, classification != null ? classification[1] : null,
                strategy, compliance, complianceReason, contacts, captures, attributionAsOf, state, amount, currency, legacyBlocks,
                merchantM4Start, syntheticSource);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static List<String> stringList(String json) {
        List<String> out = new ArrayList<>();
        JSON.readTree(json).forEach(n -> out.add(n.stringValue()));
        return out;
    }
}
