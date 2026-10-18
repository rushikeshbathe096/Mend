package com.mend.domain.repository;

import com.mend.domain.entity.EvalSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface EvalSnapshotRepository extends JpaRepository<EvalSnapshot, UUID> {

    List<EvalSnapshot> findByWindowFromAndWindowToAndAsOfAndSha256(Instant windowFrom, Instant windowTo, Instant asOf, String sha256);

    List<EvalSnapshot> findTop50ByOrderByCreatedAtDesc();
}
