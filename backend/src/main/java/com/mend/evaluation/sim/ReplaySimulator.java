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
import com.mend.strategy.ActionPolicy;
import com.mend.strategy.RecoveryContext;
import com.mend.strategy.RecoveryDecision;
import com.mend.strategy.RecoveryStrategyEngine;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.PaymentFailureClass;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * M4 U4.5 (ADR-004 §7, AGENTS.md invariant 25): replays failures through the PRODUCTION policy code — {@link FailureTaxonomy},
 * {@link ActionPolicy} (eligibility, retry delay, strategy alternatives), {@link RecoveryStrategyEngine}, {@link ComplianceEngine},
 * {@link HoldoutAssigner} — with a seeded {@link OutcomeModel}. In memory: no database, no table, no Spring. All output is
 * SYNTHETIC (provider mode {@code simulator}).
 *
 * <p>Fixed assumptions (documented, U4.5 review): one decision per campaign at first eligibility (attempt count 0); no
 * opt-out; no human approves a MANUAL_REVIEW item; a campaign whose original payment was captured before MEND's contact is
 * RECOVERED, so the engines see it terminal and nothing is dispatched; one in-flight action per invoice (a link is in
 * flight for its 72 h validity): a later campaign of the invoice gets {@code duplicateActionExists}; a later campaign of an
 * assigned invoice copies its arm while the holdout is on (INVOICE_REUSE, DECISION U4.1-e); a contacted, unpaid campaign
 * is in production's EXECUTING state.
 *
 * <p>Outcome draws use common random numbers keyed by (seed, campaign id), with one sub-stream per draw type, so two
 * policies replayed on the same events differ only through their decisions (a paired comparison).
 */
public final class ReplaySimulator {

    public static final String PROVIDER_MODE = "simulator";

    /** The production engines this simulator calls (exposed so a test can prove they are not copies). */
    final RecoveryStrategyEngine strategyEngine = new RecoveryStrategyEngine();
    final ComplianceEngine complianceEngine = new ComplianceEngine();

    /**
     * @param contactWindowHours     merchant config (drives RETRY_LATER's delay through production's rule); null = entity default
     * @param enabledRecoveryActions merchant config (strategy restriction); null = unrestricted
     */
    public record Config(long seed, int holdoutRateBp, int horizonDays, PolicyVariant variant, OutcomeModel outcomes,
                         Instant windowStart, Duration window, Integer contactWindowHours, String enabledRecoveryActions) {

        public Config(long seed, int holdoutRateBp, int horizonDays, PolicyVariant variant, OutcomeModel outcomes,
                      Instant windowStart, Duration window) {
            this(seed, holdoutRateBp, horizonDays, variant, outcomes, windowStart, window, null, null);
        }
    }

    /** Per-event result, before snapshot building (also used for the paired policy comparison). */
    /** {@code noActionCapture}: the late capture within the horizon that happens whatever MEND does (the counterfactual). */
    public record Outcome(ReplayEvent event, PaymentFailureClass failureClass, boolean eligible, Arm arm, RecoveryStrategy strategy,
                          boolean allowed, boolean contacted, Instant firstCapture, CaptureSource source, Instant noActionCapture) {
        public long recoveredMinor() {
            return firstCapture != null ? event.amountMinor() : 0L;
        }
    }

    public record Result(Config config, List<Outcome> outcomes, SnapshotParams params, SnapshotBuilder.Content content,
                         Pseudonyms ids) {
    }

    private record InvoiceState(Arm arm, int rateBp, int propensityBp, Instant linkInFlightUntil) {
    }

    public Result run(List<ReplayEvent> events, Config config) {
        Instant asOf = config.windowStart().plus(config.window()).plus(Duration.ofDays(config.horizonDays()));
        Duration horizon = Duration.ofDays(config.horizonDays());
        Map<UUID, MerchantConfig> merchantConfigs = new HashMap<>();
        Map<UUID, byte[]> evalSalts = new HashMap<>();
        Map<String, InvoiceState> invoices = new HashMap<>();
        Outcome[] outcomes = new Outcome[events.size()];
        List<CampaignFacts> facts = new ArrayList<>();
        List<DecisionFacts> decisions = new ArrayList<>();

        // Production order: campaigns become eligible one after the other; ties by campaign id.
        Integer[] order = new Integer[events.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing((Integer i) -> events.get(i).eligibleAt()).thenComparing(i -> events.get(i).campaignId()));

        for (int index : order) {
            ReplayEvent e = events.get(index);
            evalSalts.computeIfAbsent(e.merchantId(), m -> salt(config.seed(), m, "eval-id"));
            MerchantConfig merchantConfig = merchantConfigs.computeIfAbsent(e.merchantId(), m -> merchantConfig(m, config));
            SplittableRandom draws = draws(config.seed(), e.campaignId());
            SplittableRandom lateDraws = draws.split();
            SplittableRandom linkDraws = draws.split();
            SplittableRandom retryDraws = draws.split();

            ClassificationResult classification = classify(e);
            PaymentFailureClass cls = PaymentFailureClass.fromStored(classification.getFailureClass());
            PaymentFailureClass modelClass = cls != null ? cls : PaymentFailureClass.UNCLASSIFIED;
            double lateHours = config.outcomes().lateCaptureHours(modelClass, lateDraws, config.horizonDays());
            double linkHours = config.outcomes().linkPayHours(modelClass, linkDraws);
            Instant horizonEnd = e.eligibleAt().plus(horizon);
            Instant lateCapture = lateHours >= 0 ? at(e.eligibleAt(), lateHours) : null;

            boolean eligible = ActionPolicy.eligible(classification)
                    && !(config.variant() == PolicyVariant.A_UNCLASSIFIED_INELIGIBLE && cls == PaymentFailureClass.UNCLASSIFIED);
            if (!eligible) {
                outcomes[index] = new Outcome(e, cls, false, null, null, false, false, cap(lateCapture, horizonEnd),
                        cap(lateCapture, horizonEnd) == null ? null : CaptureSource.ORIGINAL, cap(lateCapture, horizonEnd));
                continue; // never ELIGIBLE: outside the evaluation population, as in production (EXHAUSTED)
            }

            InvoiceState previous = invoices.get(e.invoiceKey());
            Arm arm;
            int rate;
            int propensity;
            String source;
            if (previous != null && config.holdoutRateBp() > 0) {
                arm = previous.arm();
                rate = previous.rateBp();
                propensity = previous.propensityBp();
                source = "INVOICE_REUSE";
            } else {
                int bucket = HoldoutAssigner.bucket(salt(config.seed(), e.merchantId(), "assignment"), e.invoiceKey());
                rate = config.holdoutRateBp();
                arm = HoldoutAssigner.arm(bucket, rate);
                propensity = HoldoutAssigner.propensityBp(arm, rate);
                source = "HASH";
            }
            decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), null, "ASSIGNMENT", arm.name(), source,
                    rate > 0 ? List.of("CONTROL", "TREATMENT") : List.of("TREATMENT"), propensity, classification.getTaxonomyVersion(),
                    RecoveryStrategyEngine.POLICY_VERSION, ComplianceEngine.POLICY_VERSION, HoldoutAssigner.HASH_VERSION, e.eligibleAt()));

            List<Capture> captures = new ArrayList<>();
            if (lateCapture != null) {
                captures.add(new Capture(lateCapture, CaptureSource.ORIGINAL));
            }
            RecoveryStrategy strategy = null;
            ComplianceDecision compliance = null;
            List<Instant> contacts = new ArrayList<>();
            Instant linkUntil = previous != null ? previous.linkInFlightUntil() : null;
            if (arm == Arm.TREATMENT) {
                Campaign campaign = new Campaign(e.campaignId(), e.merchantId());
                campaign.setCurrentState(CampaignStatus.ELIGIBLE);
                campaign.setInvoiceKey(e.invoiceKey());
                campaign.setFailureClass(classification.getFailureClass());
                RecoveryDecision decision = strategyEngine.evaluate(new RecoveryContext(campaign, classification, merchantConfig));
                strategy = decision.getStrategy();
                campaign.setStrategy(strategy.name());
                boolean inFlight = linkUntil != null && linkUntil.isAfter(e.eligibleAt());
                compliance = complianceEngine.evaluate(new ComplianceContext(e.merchantId(), campaign, strategy, classification,
                        merchantConfig, null, inFlight));
                decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), null, "STRATEGY", strategy.name(), null,
                        ActionPolicy.strategyAlternatives(strategy, merchantConfig), 10_000, classification.getTaxonomyVersion(),
                        decision.getPolicyVersion(), ComplianceEngine.POLICY_VERSION, HoldoutAssigner.HASH_VERSION, e.eligibleAt().plusSeconds(1)));
                decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), null, "COMPLIANCE", compliance.isBlocked() ? "BLOCKED" : "ALLOWED",
                        compliance.getReason().name(), List.of("ALLOWED", "BLOCKED"), 10_000, classification.getTaxonomyVersion(),
                        decision.getPolicyVersion(), compliance.getPolicyVersion(), HoldoutAssigner.HASH_VERSION, e.eligibleAt().plusSeconds(2)));
                if (compliance.isAllowed() && strategy != RecoveryStrategy.MANUAL_REVIEW && strategy != RecoveryStrategy.NO_ACTION) {
                    Duration delay = ActionPolicy.scheduleDelay(strategy, merchantConfig, 15);
                    Instant contact = e.eligibleAt().plus(delay).plusSeconds(60);
                    // the original payment was captured first: the campaign is RECOVERED, the pre-dispatch check stops the action
                    if (lateCapture == null || lateCapture.isAfter(contact)) {
                        contacts.add(contact);
                        UUID intent = UUID.nameUUIDFromBytes(("sim-intent:" + e.campaignId()).getBytes(StandardCharsets.UTF_8));
                        decisions.add(new DecisionFacts(e.merchantId(), e.campaignId(), intent, "DISPATCH", "DISPATCHED", null,
                                List.of("BLOCKED", "DISPATCHED", "EXPIRED"), 10_000, classification.getTaxonomyVersion(),
                                decision.getPolicyVersion(), compliance.getPolicyVersion(), HoldoutAssigner.HASH_VERSION, contact));
                        if (strategy == RecoveryStrategy.CUSTOMER_ACTION_REQUIRED) {
                            linkUntil = contact.plus(OutcomeModel.LINK_TTL);
                            if (linkHours >= 0) {
                                captures.add(new Capture(at(contact, linkHours), CaptureSource.MEND));
                            }
                        } else if (config.outcomes().retriesWired() && config.outcomes().retryCaptures(delay, retryDraws)) {
                            captures.add(new Capture(contact, CaptureSource.MEND));
                        }
                    }
                }
            }
            invoices.put(e.invoiceKey(), previous != null ? new InvoiceState(previous.arm(), previous.rateBp(), previous.propensityBp(), linkUntil)
                    : new InvoiceState(arm, rate, propensity, linkUntil));

            captures.removeIf(c -> c.at().isAfter(asOf));
            Capture first = captures.stream().filter(c -> !c.at().isAfter(horizonEnd)).min(Comparator.comparing(Capture::at)).orElse(null);
            Capture firstAny = captures.stream().min(Comparator.comparing(Capture::at)).orElse(null);
            facts.add(new CampaignFacts(e.merchantId(), e.campaignId(), e.invoiceKey(), null, e.eligibleAt(),
                    new CampaignFacts.Assignment(arm.name(), rate, propensity, source, 1, HoldoutAssigner.HASH_VERSION),
                    classification.getFailureClass(), classification.getTaxonomyVersion(),
                    strategy == null ? null : strategy.name(), compliance == null ? null : (compliance.isBlocked() ? "BLOCKED" : "ALLOWED"),
                    compliance == null ? null : compliance.getReason().name(), contacts, captures,
                    firstAny == null ? null : firstAny.source() == CaptureSource.MEND ? "MEND" : "ORIGINAL_PAYMENT_LATE_CAPTURE",
                    firstAny != null ? "RECOVERED" : contacts.isEmpty() ? "ELIGIBLE" : "EXECUTING", e.amountMinor(), "INR", 0,
                    config.windowStart(), true));
            outcomes[index] = new Outcome(e, cls, true, arm, strategy, compliance != null && compliance.isAllowed(), !contacts.isEmpty(),
                    first == null ? null : first.at(), first == null ? null : first.source(), cap(lateCapture, horizonEnd));
        }

        SnapshotParams params = new SnapshotParams(config.windowStart(), config.windowStart().plus(config.window()), asOf,
                config.horizonDays(), config.windowStart(), PROVIDER_MODE, List.of());
        Pseudonyms ids = new Pseudonyms(evalSalts);
        return new Result(config, List.of(outcomes), params, SnapshotBuilder.build(facts, decisions, ids, params), ids);
    }

    private static MerchantConfig merchantConfig(UUID merchantId, Config config) {
        MerchantConfig c = new MerchantConfig(UUID.nameUUIDFromBytes(("sim-config:" + merchantId).getBytes(StandardCharsets.UTF_8)), merchantId);
        c.setHoldoutRateBp(config.holdoutRateBp());
        if (config.contactWindowHours() != null) {
            c.setContactWindowHours(config.contactWindowHours());
        }
        c.setEnabledRecoveryActions(config.enabledRecoveryActions());
        return c;
    }

    private static ClassificationResult classify(ReplayEvent e) {
        String cls;
        String version;
        if (e.failure() != null) {
            cls = FailureTaxonomy.classify(e.failure()).failureClass().name();
            version = FailureTaxonomy.TABLE_VERSION;
        } else {
            cls = e.storedClass();
            version = e.storedTaxonomyVersion();   // a legacy row keeps none, so it fails closed as in production
        }
        ClassificationResult result = new ClassificationResult(null, null, e.campaignId(), cls, null, null, null, null);
        result.setTaxonomyVersion(version);
        return result;
    }

    private static Instant cap(Instant capture, Instant horizonEnd) {
        return capture == null || capture.isAfter(horizonEnd) ? null : capture;
    }

    private static Instant at(Instant base, double hours) {
        return base.plusSeconds(Math.round(hours * 3600));
    }

    /** One generator per (seed, campaign): reordering or filtering the stream never changes an event's draws. */
    static SplittableRandom draws(long seed, UUID campaignId) {
        return new SplittableRandom(mix64(seed ^ mix64(campaignId.getMostSignificantBits()) ^ Long.rotateLeft(campaignId.getLeastSignificantBits(), 17)));
    }

    /** Domain-separated per-merchant salt (32 bytes) derived from (seed, merchant, purpose). */
    static byte[] salt(long seed, UUID merchantId, String purpose) {
        long h = mix64(seed ^ mix64(merchantId.getMostSignificantBits() ^ merchantId.getLeastSignificantBits())
                ^ mix64(purpose.hashCode()));
        ByteBuffer out = ByteBuffer.allocate(HoldoutAssigner.SALT_BYTES);
        for (int i = 0; i < 4; i++) {
            h = mix64(h + 0x9E3779B97F4A7C15L);
            out.putLong(h);
        }
        return out.array();
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
