package com.mend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class UserDto {

    private UUID id;
    private String email;
    private String displayName;
    private String status;
    private Instant createdAt;
    private List<MerchantMembershipDto> memberships;

    public UserDto() {
    }

    public UserDto(UUID id, String email, String displayName, String status, Instant createdAt, List<MerchantMembershipDto> memberships) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.createdAt = createdAt;
        this.memberships = memberships;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<MerchantMembershipDto> getMemberships() {
        return memberships;
    }

    public void setMemberships(List<MerchantMembershipDto> memberships) {
        this.memberships = memberships;
    }
}
