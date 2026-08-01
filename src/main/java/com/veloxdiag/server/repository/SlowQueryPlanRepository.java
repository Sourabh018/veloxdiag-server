package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.SlowQueryPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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

    // Deletes every slow-query-plan row for one application — paired with
    // TelemetryRepository.deleteByApplicationName so a Settings-page reset
    // clears both tables for that app, not just telemetry (otherwise Slow
    // Queries/Index Advisor pages would still show stale plans after a reset).
    @Modifying
    @Transactional
    @Query("DELETE FROM SlowQueryPlan p WHERE p.applicationName = :applicationName")
    int deleteByApplicationName(String applicationName);
}