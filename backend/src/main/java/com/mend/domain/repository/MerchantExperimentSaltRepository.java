package com.mend.domain.repository;

import com.mend.domain.entity.MerchantExperimentSalt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantExperimentSaltRepository extends JpaRepository<MerchantExperimentSalt, MerchantExperimentSalt.Key> {

    Optional<MerchantExperimentSalt> findFirstByMerchantIdAndPurposeOrderBySaltVersionDesc(UUID merchantId, String purpose);

    /** M4 U4.1: first salt of a merchant for a purpose; a concurrent creator wins and the loser re-reads (no exception). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO merchant_experiment_salts (merchant_id, purpose, salt_version, salt)
            VALUES (:merchantId, :purpose, :saltVersion, :salt)
            ON CONFLICT (merchant_id, purpose, salt_version) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("merchantId") UUID merchantId, @Param("purpose") String purpose,
                       @Param("saltVersion") int saltVersion, @Param("salt") byte[] salt);
}
