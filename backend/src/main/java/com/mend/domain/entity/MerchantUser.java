package com.mend.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_users", 
    indexes = {
        @Index(name = "idx_merchant_users_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_merchant_users_user_id", columnList = "user_id"),
        @Index(name = "idx_merchant_users_role_id", columnList = "role_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_merchant_user", columnNames = {"merchant_id", "user_id"})
    }
)
public class MerchantUser {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;

    @Column(name = "role_id", nullable = false, columnDefinition = "UUID")
    private UUID roleId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public MerchantUser() {
    }

    public MerchantUser(UUID id, UUID merchantId, UUID userId, UUID roleId) {
        this.id = id;
        this.merchantId = merchantId;
        this.userId = userId;
        this.roleId = roleId;
        this.createdAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
