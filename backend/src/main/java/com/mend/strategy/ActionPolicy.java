package com.mend.strategy;

import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.RecoveryStrategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * M4 U4.5 review: deterministic policy rules shared by the production services and the replay simulator, so the simulator
 * calls the same code instead of a copy (AGENTS.md invariant 25). No Spring, no persistence.
 */
public final class ActionPolicy {

    private ActionPolicy() {
    }

    /**
     * ADR-003 §4: eligible iff the classification row is deterministic (taxonomy version). Used by
     * {@code CampaignLifecycleService.evaluateEligibility}.
     */
    public static boolean eligible(ClassificationResult classification) {
        return classification != null && classification.deterministicClass();
    }

    /**
     * When an intent for {@code strategy} is due, relative to its creation: RETRY_LATER waits the merchant's contact window
     * (hours), else {@code defaultRetryDelayMinutes}; every other strategy is due at once. Used by
     * {@code ActionIntentService.calculateScheduledTime}.
     */
    public static Duration scheduleDelay(RecoveryStrategy strategy, MerchantConfig config, long defaultRetryDelayMinutes) {
        if (strategy == RecoveryStrategy.RETRY_LATER) {
            if (config != null && config.getContactWindowHours() != null) {
                return Duration.ofMinutes(config.getContactWindowHours() * 60L);
            }
            return Duration.ofMinutes(defaultRetryDelayMinutes);
        }
        return Duration.ZERO;
    }

    /**
     * M4 U4.2: the strategies the policy could have chosen for this merchant — every strategy when the merchant config does not
     * restrict them, else the enabled ones plus MANUAL_REVIEW and NO_ACTION (the same matching as RecoveryStrategyEngine rule
     * 5). Always contains the chosen strategy; sorted.
     */
    public static List<String> strategyAlternatives(RecoveryStrategy chosen, MerchantConfig config) {
        String enabled = config != null && config.getEnabledRecoveryActions() != null
                ? config.getEnabledRecoveryActions().toUpperCase(Locale.ROOT) : "";
        TreeSet<String> alternatives = new TreeSet<>();
        for (RecoveryStrategy s : RecoveryStrategy.values()) {
            if (enabled.isBlank() || enabled.contains("ALL") || enabled.contains(s.name())
                    || s == RecoveryStrategy.MANUAL_REVIEW || s == RecoveryStrategy.NO_ACTION) {
                alternatives.add(s.name());
            }
        }
        alternatives.add(chosen.name());
        return new ArrayList<>(alternatives);
    }
}
