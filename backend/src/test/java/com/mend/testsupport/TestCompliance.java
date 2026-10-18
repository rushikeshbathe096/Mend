package com.mend.testsupport;

import com.mend.compliance.ComplianceEngine;

/**
 * Policy version for hand-built compliance decisions in tests.
 *
 * <p>M3 follow-up (behaviour change, ADR-003 Consequences): the executor refuses to dispatch on a decision made before
 * the deterministic policy (v1.x or unknown → LEGACY_DECISION). Fixtures that only need "an ALLOWED decision" therefore
 * use the current policy version; they used "v1.0.0" / "v1" / "v1.0" before. A test that means a pre-M3 decision says
 * so explicitly (LegacyDecisionDispatchTest).
 */
public final class TestCompliance {

    public static final String CURRENT_POLICY = ComplianceEngine.POLICY_VERSION;

    private TestCompliance() {
    }
}
