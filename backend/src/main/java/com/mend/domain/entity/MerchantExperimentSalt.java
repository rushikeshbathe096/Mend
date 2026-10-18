package com.mend.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * M4 U4.1: a per-merchant random salt (32 bytes, generated at runtime, never written to a file or a log). A rotation
 * inserts a new version; a salt row never changes (V22 trigger). Inserted only through
 * {@code MerchantExperimentSaltRepository.insertIfAbsent}.
 */
@Entity
@Immutable
@IdClass(MerchantExperimentSalt.Key.class)
@Table(name = "merchant_experiment_salts")
public class MerchantExperimentSalt {

    public static final String ASSIGNMENT = "ASSIGNMENT";
    public static final String EVAL_ID = "EVAL_ID";

    @Id
    @Column(name = "merchant_id", columnDefinition = "UUID")
    private UUID merchantId;

    @Id
    @Column(nullable = false, length = 20)
    private String purpose;

    @Id
    @Column(name = "salt_version")
    private int saltVersion;

    @Column(nullable = false)
    private byte[] salt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantExperimentSalt() {
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getPurpose() {
        return purpose;
    }

    public int getSaltVersion() {
        return saltVersion;
    }

    public byte[] getSalt() {
        return salt.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static class Key implements Serializable {
        private UUID merchantId;
        private String purpose;
        private int saltVersion;

        public Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && saltVersion == k.saltVersion && Objects.equals(merchantId, k.merchantId)
                    && Objects.equals(purpose, k.purpose);
        }

        @Override
        public int hashCode() {
            return Objects.hash(merchantId, purpose, saltVersion);
        }
    }
}
