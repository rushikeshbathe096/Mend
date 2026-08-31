package com.mend.domain.repository;

import com.mend.domain.entity.MerchantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantConfigRepository extends JpaRepository<MerchantConfig, UUID> {
    Optional<MerchantConfig> findByMerchantId(UUID merchantId);
}
