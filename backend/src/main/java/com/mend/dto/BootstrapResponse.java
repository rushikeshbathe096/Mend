package com.mend.dto;

import java.util.UUID;

public class BootstrapResponse {

    private UUID merchantId;
    private String merchantName;
    private UUID userId;
    private String userEmail;
    private String roleName;

    public BootstrapResponse() {
    }

    public BootstrapResponse(UUID merchantId, String merchantName, UUID userId, String userEmail, String roleName) {
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.userId = userId;
        this.userEmail = userEmail;
        this.roleName = roleName;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
}
