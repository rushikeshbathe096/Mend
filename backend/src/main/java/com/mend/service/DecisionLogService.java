package com.mend.service;

import com.mend.compliance.ComplianceDecision;
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

    public DecisionLogService(DecisionLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry assignment(HoldoutAssignment a) {
        List<String> alternatives = a.getHoldoutRateBp() > 0
                ? List.of(Arm.CONTROL.name(), Arm.TREATMENT.name()) : List.of(Arm.TREATMENT.name());
        return save(new DecisionLogEntry(a.getMerchantId(), a.getCampaignId(), null, Point.ASSIGNMENT, a.getArm().name(), a.getSource(),
                alternatives, HoldoutAssigner.propensityBp(a.getArm(), a.getHoldoutRateBp()), a.getCampaignId(),
                null, null, null, a.getHashVersion(), Instant.now()));
    }

    /** {@code decisionId}: the persisted recovery_decisions row; {@code taxonomyVersion}: of the classification it used. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry strategy(RecoveryDecision decision, UUID decisionId, String taxonomyVersion, MerchantConfig config) {
        return save(new DecisionLogEntry(decision.getMerchantId(), decision.getCampaignId(), null, Point.STRATEGY,
                decision.getStrategy().name(), null, strategyAlternatives(decision.getStrategy(), config), DETERMINISTIC_BP, decisionId,
                taxonomyVersion, decision.getPolicyVersion(), null, null, Instant.now()));
    }

    /** {@code decisionId}: the persisted compliance_decisions row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry compliance(ComplianceDecision decision, UUID decisionId, String taxonomyVersion, String strategyVersion) {
        String chosen = decision.isBlocked() ? BLOCKED : "ALLOWED";
        return save(new DecisionLogEntry(decision.getMerchantId(), decision.getCampaignId(), null, Point.COMPLIANCE, chosen,
                decision.getReason() != null ? decision.getReason().name() : null, List.of("ALLOWED", BLOCKED), DETERMINISTIC_BP,
                decisionId, taxonomyVersion, strategyVersion, decision.getPolicyVersion(), null, Instant.now()));
    }

    /** {@code outcome}: DISPATCHED, BLOCKED or EXPIRED; {@code reasonCode}: the block / expiry code, ids and codes only. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DecisionLogEntry dispatch(UUID merchantId, UUID campaignId, UUID intentId, String outcome, String reasonCode,
                                     String complianceVersion) {
        if (!DISPATCH_ALTERNATIVES.contains(outcome)) {
            throw new IllegalArgumentException("unknown dispatch outcome " + outcome);
        }
        return save(new DecisionLogEntry(merchantId, campaignId, intentId, Point.DISPATCH, outcome, reasonCode, DISPATCH_ALTERNATIVES,
                DETERMINISTIC_BP, intentId, null, null, complianceVersion, null, Instant.now()));
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
