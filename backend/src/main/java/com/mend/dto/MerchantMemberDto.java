package com.mend.dto;

import java.time.Instant;
import java.util.UUID;

public class MerchantMemberDto {

    private UUID userId;
    private String email;
    private String displayName;
    private String userStatus;
    private UUID roleId;
    private String roleName;
    private Instant joinedAt;

    public MerchantMemberDto() {
    }

    public MerchantMemberDto(UUID userId, String email, String displayName, String userStatus, UUID roleId, String roleName, Instant joinedAt) {
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.userStatus = userStatus;
        this.roleId = roleId;
        this.roleName = roleName;
        this.joinedAt = joinedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUserStatus() {
        return userStatus;
    }

    public void setUserStatus(String userStatus) {
        this.userStatus = userStatus;
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

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
