package com.mend.security;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class AuthenticatedUser {

    private final UUID userId;
    private final String email;
    private final String displayName;
    private final String status;
    private final List<MerchantMembershipInfo> memberships;

    public AuthenticatedUser(UUID userId, String email, String displayName, String status, List<MerchantMembershipInfo> memberships) {
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.memberships = memberships != null ? Collections.unmodifiableList(memberships) : Collections.emptyList();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public List<MerchantMembershipInfo> getMemberships() {
        return memberships;
    }

    public boolean isSystemAdmin() {
        return memberships.stream().anyMatch(m -> "SYSTEM_ADMIN".equalsIgnoreCase(m.getRoleName()));
    }

    public boolean isMemberOfMerchant(UUID merchantId) {
        if (merchantId == null) return false;
        if (isSystemAdmin()) return true;
        return memberships.stream().anyMatch(m -> Objects.equals(m.getMerchantId(), merchantId));
    }

    public boolean hasMerchantRole(UUID merchantId, String requiredRole) {
        if (merchantId == null || requiredRole == null) return false;
        if (isSystemAdmin()) return true;
        return memberships.stream()
                .filter(m -> Objects.equals(m.getMerchantId(), merchantId))
                .anyMatch(m -> requiredRole.equalsIgnoreCase(m.getRoleName()) || "MERCHANT_ADMIN".equalsIgnoreCase(m.getRoleName()));
    }

    public String getRoleForMerchant(UUID merchantId) {
        if (merchantId == null) return null;
        return memberships.stream()
                .filter(m -> Objects.equals(m.getMerchantId(), merchantId))
                .map(MerchantMembershipInfo::getRoleName)
                .findFirst()
                .orElse(isSystemAdmin() ? "SYSTEM_ADMIN" : null);
    }

    public static class MerchantMembershipInfo {
        private final UUID merchantId;
        private final String merchantName;
        private final UUID roleId;
        private final String roleName;

        public MerchantMembershipInfo(UUID merchantId, String merchantName, UUID roleId, String roleName) {
            this.merchantId = merchantId;
            this.merchantName = merchantName;
            this.roleId = roleId;
            this.roleName = roleName;
        }

        public UUID getMerchantId() {
            return merchantId;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public UUID getRoleId() {
            return roleId;
        }

        public String getRoleName() {
            return roleName;
        }
    }
}
