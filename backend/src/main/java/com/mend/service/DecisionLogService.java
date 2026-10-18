package com.mend.service;

import com.mend.compliance.ComplianceDecision;
import com.mend.compliance.ComplianceEngine;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.HoldoutAssignmentRepository;
import com.mend.strategy.RecoveryStrategyEngine;
import com.mend.domain.entity.DecisionLogEntry;
import com.mend.domain.entity.DecisionLogEntry.Point;
import com.mend.domain.entity.HoldoutAssignment;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.DecisionLogRepository;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import com.mend.strategy.RecoveryDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/**
 * M4 U4.2 (ADR-004 §4, AGENTS.md invariant 21): appends one decision-log row per decision point, in the caller's
 * transaction (MANDATORY), so the row commits or rolls back with the decision itself. A failed insert is never swallowed.
 * Probabilities are basis points: the deterministic policy's choices have {@link #DETERMINISTIC_BP}.
 */
@Service
public class DecisionLogService {

    public static final int DETERMINISTIC_BP = 10_000;

    /** Dispatch outcomes recorded at the executor's fence. */
    public static final String DISPATCHED = "DISPATCHED";
    public static final String BLOCKED = "BLOCKED";
    public static final String EXPIRED = "EXPIRED";
    private static final List<String> DISPATCH_ALTERNATIVES = List.of(BLOCKED, DISPATCHED, EXPIRED);

    private final DecisionLogRepository repository;
    private final HoldoutAssignmentRepository assignmentRepository;
    private final ClassificationResultRepository classificationResultRepository;

    public DecisionLogService(DecisionLogRepository repository, HoldoutAssignmentRepository assignmentRepository,
                              ClassificationResultRepository classificationResultRepository) {
        this.repository = repository;
        this.assignmentRepository = assignmentRepository;
        this.classificationResultRepository = classificationResultRepository;
    }

    /**
     * U4.2 review: every row carries the versions in force when the decision was taken. Taxonomy: the version of the
     * campaign's latest classification (null for a legacy row); strategy and compliance: the deterministic engines'
     * versions (the code that decides); assignment: the campaign's stored hash version (null = UNASSIGNED, pre-M4).
     */
    private record Versions(String taxonomy, String strategy, String compliance, String assignment) {
    }

    private Versions inForce(UUID campaignId) {
        String taxonomy = classificationResultRepository.findLatestByCampaignId(campaignId)
                .map(ClassificationResult::getTaxonomyVersion).orElse(null);
        String assignment = assignmentRepository.findById(campaignId).map(HoldoutAssignment::getHashVersion).orElse(null);
        return new Versions(taxonomy, RecoveryStrategyEngine.POLICY_VERSION, ComplianceEngine.POLICY_VERSION, assignment);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry assignment(HoldoutAssignment a) {
        List<String> alternatives = a.getHoldoutRateBp() > 0
                ? List.of(Arm.CONTROL.name(), Arm.TREATMENT.name()) : List.of(Arm.TREATMENT.name());
        Versions v = inForce(a.getCampaignId());
        return save(new DecisionLogEntry(a.getMerchantId(), a.getCampaignId(), null, Point.ASSIGNMENT, a.getArm().name(), a.getSource(),
                alternatives, HoldoutAssigner.propensityBp(a.getArm(), a.getHoldoutRateBp()), a.getCampaignId(),
                v.taxonomy(), v.strategy(), v.compliance(), a.getHashVersion(), Instant.now()));
    }

    /** {@code decisionId}: the persisted recovery_decisions row; {@code taxonomyVersion}: of the classification it used. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry strategy(RecoveryDecision decision, UUID decisionId, String taxonomyVersion, MerchantConfig config) {
        Versions v = inForce(decision.getCampaignId());
        return save(new DecisionLogEntry(decision.getMerchantId(), decision.getCampaignId(), null, Point.STRATEGY,
                decision.getStrategy().name(), null, strategyAlternatives(decision.getStrategy(), config), DETERMINISTIC_BP, decisionId,
                taxonomyVersion, decision.getPolicyVersion(), v.compliance(), v.assignment(), Instant.now()));
    }

    /**
     * {@code decisionId}: the persisted compliance_decisions row. {@code strategyVersion}: the version of the recovery decision
     * evaluated, or null when a human-approved override strategy was evaluated instead (OD-1; no policy chose it).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry compliance(ComplianceDecision decision, UUID decisionId, String taxonomyVersion, String strategyVersion) {
        String chosen = decision.isBlocked() ? BLOCKED : "ALLOWED";
        Versions v = inForce(decision.getCampaignId());
        return save(new DecisionLogEntry(decision.getMerchantId(), decision.getCampaignId(), null, Point.COMPLIANCE, chosen,
                decision.getReason() != null ? decision.getReason().name() : null, List.of("ALLOWED", BLOCKED), DETERMINISTIC_BP,
                decisionId, taxonomyVersion, strategyVersion, decision.getPolicyVersion(), v.assignment(), Instant.now()));
    }

    /**
     * {@code outcome}: DISPATCHED, BLOCKED or EXPIRED; {@code reasonCode}: the block / expiry code, ids and codes only;
     * {@code complianceVersion}: of the stored decision that authorized (or failed to authorize) the dispatch, when known,
     * else the engine's. One intent can have several DISPATCH rows: a deferred intent is dispatched again (M2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry dispatch(UUID merchantId, UUID campaignId, UUID intentId, String outcome, String reasonCode,
                                     String complianceVersion) {
        if (!DISPATCH_ALTERNATIVES.contains(outcome)) {
            throw new IllegalArgumentException("unknown dispatch outcome " + outcome);
        }
        Versions v = inForce(campaignId);
        return save(new DecisionLogEntry(merchantId, campaignId, intentId, Point.DISPATCH, outcome, reasonCode, DISPATCH_ALTERNATIVES,
                DETERMINISTIC_BP, intentId, v.taxonomy(), v.strategy(), complianceVersion != null ? complianceVersion : v.compliance(),
                v.assignment(), Instant.now()));
    }

    /**
     * The strategies the policy could have chosen for this merchant: every strategy when the merchant config does not
     * restrict them, else the enabled ones plus the two the engine always may fall back to (MANUAL_REVIEW, NO_ACTION).
     * Always contains the chosen strategy. Sorted, so the row is reproducible.
     */
    static List<String> strategyAlternatives(RecoveryStrategy chosen, MerchantConfig config) {
        String enabled = config != null && config.getEnabledRecoveryActions() != null
                ? config.getEnabledRecoveryActions().toUpperCase(Locale.ROOT) : "";
        TreeSet<String> alternatives = new TreeSet<>();
        for (RecoveryStrategy s : RecoveryStrategy.values()) {
            // same matching rule as RecoveryStrategyEngine rule 5 (substring of the enabled list, or ALL)
            if (enabled.isBlank() || enabled.contains("ALL") || enabled.contains(s.name())
                    || s == RecoveryStrategy.MANUAL_REVIEW || s == RecoveryStrategy.NO_ACTION) {
                alternatives.add(s.name());
            }
        }
        alternatives.add(chosen.name());
        return new ArrayList<>(alternatives);
    }

    private DecisionLogEntry save(DecisionLogEntry entry) {
        return repository.saveAndFlush(entry);
    }
}
