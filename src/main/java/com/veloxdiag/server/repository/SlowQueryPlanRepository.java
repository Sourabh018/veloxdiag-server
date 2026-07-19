package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.SlowQueryPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SlowQueryPlanRepository extends JpaRepository<SlowQueryPlan, Long> {

    List<SlowQueryPlan> findByEndpointAndContainsSeqScanTrueAndTimestampAfter(
            String endpoint, LocalDateTime cutoff);

    // Used by the dashboard's "show query plan" expand action — returns the
    // most recent captured plans for an endpoint regardless of whether they
    // contain a seq scan, so the user can inspect real EXPLAIN output on
    // demand rather than only the flagged ones.
    List<SlowQueryPlan> findTop3ByEndpointOrderByTimestampDesc(String endpoint);
}