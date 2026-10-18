package com.mend.evaluation.sim;

import com.mend.compliance.ComplianceContext;
import com.mend.compliance.ComplianceDecision;
import com.mend.compliance.ComplianceEngine;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.evaluation.snapshot.CampaignFacts;
import com.mend.evaluation.snapshot.CampaignFacts.Capture;
import com.mend.evaluation.snapshot.CampaignFacts.CaptureSource;
import com.mend.evaluation.snapshot.DecisionFacts;
import com.mend.evaluation.snapshot.Pseudonyms;
import com.mend.evaluation.snapshot.SnapshotBuilder;
import com.mend.evaluation.snapshot.SnapshotParams;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import com.mend.strategy.RecoveryContext;
import com.mend.strategy.RecoveryDecision;
import com.mend.strategy.RecoveryStrategyEngine;
import com.mend.taxonomy.FailureClassification;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.PaymentFailureClass;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * M4 U4.5 (ADR-004 §7, AGENTS.md invariant 25): replays failures through the PRODUCTION policy classes — {@link FailureTaxonomy},
 * {@link ClassificationResult#deterministicClass()} (eligibility), {@link RecoveryStrategyEngine}, {@link ComplianceEngine},
 * {@link HoldoutAssigner} — with a seeded {@link OutcomeModel}. Everything is in memory: no database, no table, no Spring.
 * All output is SYNTHETIC (provider mode {@code simulator}).
 *
 * <p>Outcome draws use common random numbers: each event's draws depend only on (seed, event), so two policies replayed
 * on the same stream differ only through their decisions (a paired comparison).
 */
public final class ReplaySimulator {

    public static final String PROVIDER_MODE = "simulator";

    /** The production engines this simulator calls (exposed so a test can prove they are not copies). */
    final RecoveryStrategyEngine strategyEngine = new RecoveryStrategyEngine();
    final ComplianceEngine complianceEngine = new ComplianceEngine();

    public record Config(long seed, int holdoutRateBp, int horizonDays, PolicyVariant variant, OutcomeModel outcomes,
                         Instant windowStart, Duration window) {
    }

    /** Per-event result, before snapshot building (also used for the paired policy comparison). */
    public record Outcome(ReplayEvent event, PaymentFailureClass failureClass, boolean eligible, Arm arm, RecoveryStrategy strategy,
                          boolean allowed, boolean contacted, Instant firstCapture, CaptureSource source) {
        public long recoveredMinor() {
            return firstCapture != null ? event.amountMinor() : 0L;
        }
    }

    public record Result(Config config, List<Outcome> outcomes, SnapshotParams params, SnapshotBuilder.Content content,
                         Pseudonyms ids) {
    }

    public Result run(List<ReplayEvent> events, Config config) {
        byte[] salt = salt(config.seed());
        Instant asOf = config.windowStart().plus(config.window()).plus(Duration.ofDays(config.horizonDays()));
        Instant horizonCap = asOf;
        MerchantConfig merchantConfig = null;
        List<Outcome> outcomes = new ArrayList<>();
        List<CampaignFacts> facts = new ArrayList<>();
        List<DecisionFacts> decisions = new ArrayList<>();
        Map<UUID, byte[]> evalSalts = new HashMap<>();

        for (int index = 0; index < events.size(); index++) {
            ReplayEvent e = events.get(index);
            evalSalts.computeIfAbsent(e.merchantId(), m -> salt(config.seed() ^ 0x5EEDL));
            if (merchantConfig == null || !merchantConfig.getMerchantId().equals(e.merchantId())) {
                merchantConfig = new MerchantConfig(UUID.nameUUIDFromBytes(e.merchantId().toString().getBytes()), e.merchantId());
                merchantConfig.setHoldoutRateBp(config.holdoutRateBp());
            }
            SplittableRandom draws = new SplittableRandom(config.seed() * 1_000_003L + index);
            double lateHours = config.outcomes().lateCaptureHours(classOf(e), draws, config.horizonDays());
            double linkHours = config.outcomes().linkPayHours(classOf(e), draws);
            double retryHours = config.outcomes().retryHours(draws);

            ClassificationResult classification = classify(e);
            PaymentFailureClass cls = PaymentFailureClass.fromStored(classification.getFailureClass());
            boolean eligible = classification.deterministicClass()
                    && !(config.variant() == PolicyVariant.A_UNCLASSIFIED_INELIGIBLE && cls == PaymentFailureClass.UNCLASSIFIED);

            List<Capture> captures = new ArrayList<>();
            if (lateHours >= 0) {
                captures.add(new Capture(at(e.eligibleAt(), lateHours), CaptureSource.ORIGINAL));
            }
            if (!eligible) {
                Instant capture = captures.isEmpty() ? null : captures.get(0).at();
                outcomes.add(new Outcome(e, cls, false, null, null, false, false, capture, capture == null ? null : CaptureSource.ORIGINAL));
                continue; // never ELIGIBLE: outside the evaluation population, as in production (EXHAUSTED)
            }

            int bucket = HoldoutAssigner.bucket(salt, e.invoiceKey());
            Arm arm = HoldoutAssigner.arm(bucket, config.holdoutRateBp());
            int propensity = HoldoutAssigner.propensityBp(arm, config.holdoutRateBp());
            decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), null, "ASSIGNMENT", arm.name(), "HASH",
                    config.holdoutRateBp() > 0 ? List.of("CONTROL", "TREATMENT") : List.of("TREATMENT"), propensity,
                    FailureTaxonomy.TABLE_VERSION, RecoveryStrategyEngine.POLICY_VERSION, ComplianceEngine.POLICY_VERSION,
                    HoldoutAssigner.HASH_VERSION, e.eligibleAt()));

            RecoveryStrategy strategy = null;
            ComplianceDecision compliance = null;
            List<Instant> contacts = new ArrayList<>();
            if (arm == Arm.TREATMENT) {
                Campaign campaign = new Campaign(e.campaignId(), e.merchantId());
                campaign.setCurrentState(CampaignStatus.ELIGIBLE);
                campaign.setInvoiceKey(e.invoiceKey());
                campaign.setFailureClass(classification.getFailureClass());
                RecoveryDecision decision = strategyEngine.evaluate(new RecoveryContext(campaign, classification, merchantConfig));
                strategy = decision.getStrategy();
                campaign.setStrategy(strategy.name());
                compliance = complianceEngine.evaluate(new ComplianceContext(e.merchantId(), campaign, strategy, classification,
                        merchantConfig, null, false));
                decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), null, "STRATEGY", strategy.name(), null,
                        Arrays.stream(RecoveryStrategy.values()).map(Enum::name).sorted().toList(), 10_000,
                        FailureTaxonomy.TABLE_VERSION, decision.getPolicyVersion(), ComplianceEngine.POLICY_VERSION,
                        HoldoutAssigner.HASH_VERSION, e.eligibleAt().plusSeconds(1)));
                decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), null, "COMPLIANCE", compliance.isBlocked() ? "BLOCKED" : "ALLOWED",
                        compliance.getReason().name(), List.of("ALLOWED", "BLOCKED"), 10_000, FailureTaxonomy.TABLE_VERSION,
                        decision.getPolicyVersion(), compliance.getPolicyVersion(), HoldoutAssigner.HASH_VERSION, e.eligibleAt().plusSeconds(2)));
                if (compliance.isAllowed()) {
                    Instant contact = contactTime(e, strategy, config.outcomes());
                    if (contact != null) {
                        contacts.add(contact);
                        UUID intent = UUID.nameUUIDFromBytes(("sim-intent:" + e.campaignId()).getBytes());
                        decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), intent, "DISPATCH", "DISPATCHED", null,
                                List.of("BLOCKED", "DISPATCHED", "EXPIRED"), 10_000, FailureTaxonomy.TABLE_VERSION,
                                decision.getPolicyVersion(), compliance.getPolicyVersion(), HoldoutAssigner.HASH_VERSION, contact));
                        if (strategy == RecoveryStrategy.CUSTOMER_ACTION_REQUIRED && linkHours >= 0) {
                            captures.add(new Capture(at(contact, linkHours), CaptureSource.MEND));
                        } else if (strategy != RecoveryStrategy.CUSTOMER_ACTION_REQUIRED && retryHours >= 0) {
                            captures.add(new Capture(contact, CaptureSource.MEND));
                        }
                    }
                }
            }
            captures.removeIf(c -> c.at().isAfter(horizonCap));
            Capture first = captures.stream().min(Comparator.comparing(Capture::at)).orElse(null);
            boolean recovered = first != null;
            facts.add(new CampaignFacts(e.merchantId(), e.campaignId(), e.invoiceKey(), null, e.eligibleAt(),
                    new CampaignFacts.Assignment(arm.name(), config.holdoutRateBp(), propensity, "HASH", 1, HoldoutAssigner.HASH_VERSION),
                    classification.getFailureClass(), classification.getTaxonomyVersion(),
                    strategy == null ? null : strategy.name(), compliance == null ? null : (compliance.isBlocked() ? "BLOCKED" : "ALLOWED"),
                    compliance == null ? null : compliance.getReason().name(), contacts, captures,
                    !recovered ? null : first.source() == CaptureSource.MEND ? "MEND" : "ORIGINAL_PAYMENT_LATE_CAPTURE",
                    recovered ? "RECOVERED" : contacts.isEmpty() ? "ELIGIBLE" : "EXECUTING", e.amountMinor(), "INR", 0,
                    config.windowStart(), true));
            outcomes.add(new Outcome(e, cls, true, arm, strategy, compliance != null && compliance.isAllowed(), !contacts.isEmpty(),
                    recovered ? first.at() : null, recovered ? first.source() : null));
        }

        SnapshotParams params = new SnapshotParams(config.windowStart(), config.windowStart().plus(config.window()), asOf,
                config.horizonDays(), config.windowStart(), PROVIDER_MODE, List.of());
        Pseudonyms ids = new Pseudonyms(evalSalts);
        return new Result(config, List.copyOf(outcomes), params, SnapshotBuilder.build(facts, decisions, ids, params), ids);
    }

    /** When MEND's action reaches the customer / provider: a link at once; a retry only if retries are wired (else none). */
    private static Instant contactTime(ReplayEvent e, RecoveryStrategy strategy, OutcomeModel model) {
        return switch (strategy) {
            case CUSTOMER_ACTION_REQUIRED -> e.eligibleAt().plusSeconds(60);
            case RETRY_IMMEDIATELY -> model.retriesWired() ? e.eligibleAt().plusSeconds(60) : null;
            case RETRY_LATER -> model.retriesWired() ? e.eligibleAt().plus(model.retryDelay()) : null;
            case NO_ACTION, MANUAL_REVIEW -> null;
        };
    }

    private static ClassificationResult classify(ReplayEvent e) {
        String cls;
        if (e.failure() != null) {
            FailureClassification fc = FailureTaxonomy.classify(e.failure());
            cls = fc.failureClass().name();
        } else {
            cls = e.storedClass();
        }
        ClassificationResult result = new ClassificationResult(null, null, e.campaignId(), cls, null, null, null, null);
        if (PaymentFailureClass.isTaxonomyClass(cls)) {
            result.setTaxonomyVersion(FailureTaxonomy.TABLE_VERSION);
        }
        return result;
    }

    private static PaymentFailureClass classOf(ReplayEvent e) {
        String cls = e.failure() != null ? FailureTaxonomy.classify(e.failure()).failureClass().name() : e.storedClass();
        PaymentFailureClass c = PaymentFailureClass.fromStored(cls);
        return c != null ? c : PaymentFailureClass.UNCLASSIFIED;
    }

    private static Instant at(Instant base, double hours) {
        return base.plusSeconds(Math.round(hours * 3600));
    }

    static byte[] salt(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        byte[] salt = new byte[HoldoutAssigner.SALT_BYTES];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) r.nextInt(256);
        }
        return salt;
    }
}
