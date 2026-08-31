package com.mend.security;

import java.util.UUID;

public class TenantContext {

    private static final ThreadLocal<AuthenticatedUser> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_MERCHANT_ID = new ThreadLocal<>();

    public static void setTenant(AuthenticatedUser user, UUID merchantId) {
        CURRENT_USER.set(user);
        CURRENT_MERCHANT_ID.set(merchantId);
    }

    public static AuthenticatedUser getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static UUID getCurrentMerchantId() {
        return CURRENT_MERCHANT_ID.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
        CURRENT_MERCHANT_ID.remove();
    }
}
