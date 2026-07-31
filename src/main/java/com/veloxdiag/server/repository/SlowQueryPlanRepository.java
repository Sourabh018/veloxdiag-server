package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.SlowQueryPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SlowQueryPlanRepository extends JpaRepository<SlowQueryPlan, Long> {

    List<SlowQueryPlan> findByEndpointAndContainsSeqScanTrueAndTimestampAfter(
            String endpoint, LocalDateTime cutoff);

    // Batched alternative to the per-endpoint query above — fetches every
    // seq-scan plan across all endpoints in the window with a single query,
    // so callers looping over many endpoints (DiagnosisService.runDiagnosis)
    // can group in memory instead of firing one query per endpoint.
    List<SlowQueryPlan> findByContainsSeqScanTrueAndTimestampAfter(LocalDateTime cutoff);

    // Used by the dashboard's "show query plan" expand action — returns the
    // most recent captured plans for an endpoint regardless of whether they
    // contain a seq scan, so the user can inspect real EXPLAIN output on
    // demand rather than only the flagged ones.
    List<SlowQueryPlan> findTop3ByEndpointOrderByTimestampDesc(String endpoint);
}