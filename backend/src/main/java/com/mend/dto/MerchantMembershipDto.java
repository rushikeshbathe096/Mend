package com.mend.dto;

import java.util.UUID;

public class MerchantMembershipDto {

    private UUID merchantId;
    private String merchantName;
    private UUID roleId;
    private String roleName;

    public MerchantMembershipDto() {
    }

    public MerchantMembershipDto(UUID merchantId, String merchantName, UUID roleId, String roleName) {
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.roleId = roleId;
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

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
}
