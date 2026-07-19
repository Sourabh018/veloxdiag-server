package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.SlowQueryPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SlowQueryPlanRepository extends JpaRepository<SlowQueryPlan, Long> {

    List<SlowQueryPlan> findByTimestampAfter(LocalDateTime cutoff);

    List<SlowQueryPlan> findByEndpointAndContainsSeqScanTrueAndTimestampAfter(String endpoint, LocalDateTime cutoff);
}