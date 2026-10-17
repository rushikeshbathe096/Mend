package com.mend.testsupport;

import com.mend.security.AuthenticatedUser;

import java.util.List;
import java.util.UUID;

/** Authenticated users for service calls in tests: user-facing service methods require one (TenantGuard, bug sweep item 1). */
public final class TestUsers {

    private TestUsers() {
    }

    /** An ACTIVE user whose only membership is {@code role} in {@code merchantId}. */
    public static AuthenticatedUser withRole(UUID merchantId, String role) {
        return new AuthenticatedUser(UUID.randomUUID(), "user-" + UUID.randomUUID() + "@test.invalid", "Test User", "ACTIVE",
                List.of(new AuthenticatedUser.MerchantMembershipInfo(merchantId, "Test Merchant", UUID.randomUUID(), role)));
    }

    /** An ACTIVE MERCHANT_ADMIN of {@code merchantId} only. */
    public static AuthenticatedUser memberOf(UUID merchantId) {
        return withRole(merchantId, "MERCHANT_ADMIN");
    }

    /** An ACTIVE SYSTEM_ADMIN (membership in an unrelated merchant). */
    public static AuthenticatedUser systemAdmin() {
        return withRole(UUID.randomUUID(), "SYSTEM_ADMIN");
    }
}
