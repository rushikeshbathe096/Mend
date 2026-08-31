package com.mend.domain.repository;

import com.mend.domain.entity.MerchantUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantUserRepository extends JpaRepository<MerchantUser, UUID> {
    Optional<MerchantUser> findByMerchantIdAndUserId(UUID merchantId, UUID userId);
    List<MerchantUser> findByMerchantId(UUID merchantId);
    List<MerchantUser> findByUserId(UUID userId);
}
