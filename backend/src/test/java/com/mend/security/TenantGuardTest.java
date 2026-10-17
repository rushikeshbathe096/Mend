package com.mend.security;

import com.mend.exception.TenantAccessDeniedException;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Bug sweep item 1: the two entry points of the one tenant guard. */
class TenantGuardTest {

    private final UUID merchantId = UUID.randomUUID();

    @Test
    @DisplayName("User call: a member and a system admin pass")
    void memberAndSystemAdminPass() {
        assertDoesNotThrow(() -> TenantGuard.requireUserAccess(merchantId, TestUsers.memberOf(merchantId)));
        assertDoesNotThrow(() -> TenantGuard.requireUserAccess(merchantId, TestUsers.withRole(merchantId, "VIEWER")));
        assertDoesNotThrow(() -> TenantGuard.requireUserAccess(merchantId, TestUsers.systemAdmin()));
    }

    @Test
    @DisplayName("User call: another merchant's user, no user, or no merchant is denied")
    void everythingElseIsDenied() {
        assertThrows(TenantAccessDeniedException.class,
                () -> TenantGuard.requireUserAccess(merchantId, TestUsers.memberOf(UUID.randomUUID())));
        assertThrows(TenantAccessDeniedException.class, () -> TenantGuard.requireUserAccess(merchantId, null));
        assertThrows(TenantAccessDeniedException.class, () -> TenantGuard.requireUserAccess(null, TestUsers.memberOf(merchantId)));
        assertThrows(TenantAccessDeniedException.class, () -> TenantGuard.requireUserAccess(null, TestUsers.systemAdmin()));
        AuthenticatedUser noMemberships = new AuthenticatedUser(UUID.randomUUID(), "x@test.invalid", "X", "ACTIVE", null);
        assertThrows(TenantAccessDeniedException.class, () -> TenantGuard.requireUserAccess(merchantId, noMemberships));
    }

    @Test
    @DisplayName("System call: must name its caller and a merchant")
    void systemCallMustBeExplicit() {
        assertDoesNotThrow(() -> TenantGuard.requireSystemAccess(merchantId, new TenantGuard.SystemCaller("ExecutionReconciler")));
        assertThrows(TenantAccessDeniedException.class, () -> TenantGuard.requireSystemAccess(merchantId, null));
        assertThrows(TenantAccessDeniedException.class,
                () -> TenantGuard.requireSystemAccess(null, new TenantGuard.SystemCaller("ExecutionReconciler")));
        assertThrows(IllegalArgumentException.class, () -> new TenantGuard.SystemCaller(" "));
        assertThrows(IllegalArgumentException.class, () -> new TenantGuard.SystemCaller(null));
    }
}
