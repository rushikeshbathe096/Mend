package com.mend.security;

import com.mend.exception.TenantAccessDeniedException;

import java.util.UUID;

/**
 * The one tenant-access rule for service methods (bug sweep item 1). It replaces the membership check that was copied into
 * twelve services, most of which let a {@code null} user through.
 *
 * <p>Two explicit entry points, and no other way in:
 * <ul>
 *   <li>{@link #requireUserAccess} — a call on behalf of a user. The user is required; a system admin or a member of the
 *       merchant passes, anything else (including no user) is denied (403).</li>
 *   <li>{@link #requireSystemAccess} — an internal call with no user (scheduler, reconciler, webhook processing). It must
 *       name itself with a {@link SystemCaller}; there is no {@code null} that means "system".</li>
 * </ul>
 * Not a Spring bean on purpose: nothing can replace or mock it out of a service.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /** A call on behalf of {@code currentUser}: system admin or member of {@code merchantId}, else 403. */
    public static void requireUserAccess(UUID merchantId, AuthenticatedUser currentUser) {
        requireMerchant(merchantId);
        if (currentUser == null) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId + " (no authenticated user)");
        }
        if (!currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }

    /** An internal call with no user. The caller must identify itself; the merchant is still required. */
    public static void requireSystemAccess(UUID merchantId, SystemCaller caller) {
        if (caller == null) {
            throw new TenantAccessDeniedException("System call without a SystemCaller is not allowed");
        }
        requireMerchant(merchantId);
    }

    private static void requireMerchant(UUID merchantId) {
        if (merchantId == null) {
            throw new TenantAccessDeniedException("Merchant context is required");
        }
    }

    /** Names the internal code path making a system call (for example {@code "ExecutionReconciler"}). */
    public record SystemCaller(String name) {
        public SystemCaller {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SystemCaller needs a name");
            }
        }
    }
}
