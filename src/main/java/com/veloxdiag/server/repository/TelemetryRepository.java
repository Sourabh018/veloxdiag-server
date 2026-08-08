package com.veloxdiag.server.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.veloxdiag.server.entity.Telemetry;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    @Query("SELECT COUNT(t) FROM Telemetry t")
    long getTotalRequests();

    @Query("SELECT AVG(t.durationMs) FROM Telemetry t")
    Double getAverageResponseTime();

    @Query("SELECT COUNT(t) FROM Telemetry t WHERE t.status >= 400")
    long getErrorRequests();

    @Query("SELECT COUNT(DISTINCT t.applicationName) FROM Telemetry t")
    long getConnectedApplications();

    @Query("SELECT COUNT(t) as totalRequests, " +
           "AVG(t.durationMs) as averageResponseTime, " +
           "SUM(CASE WHEN t.status >= 400 THEN 1 ELSE 0 END) as errorRequests, " +
           "COUNT(DISTINCT t.applicationName) as connectedApplications " +
           "FROM Telemetry t")
    SummaryProjection getSummaryStats();

    @Query("SELECT COUNT(t) as totalRequests, " +
           "AVG(t.durationMs) as averageResponseTime, " +
           "SUM(CASE WHEN t.status >= 400 THEN 1 ELSE 0 END) as errorRequests, " +
           "COUNT(DISTINCT t.applicationName) as connectedApplications " +
           "FROM Telemetry t WHERE t.applicationName = :applicationName")
    SummaryProjection getSummaryStatsByApplication(String applicationName);

    @Query("SELECT DISTINCT t.applicationName FROM Telemetry t ORDER BY t.applicationName")
    List<String> findDistinctApplicationNames();

    List<Telemetry> findAllByOrderByTimestampDesc(Pageable pageable);

    List<Telemetry> findByApplicationNameOrderByTimestampDesc(String applicationName, Pageable pageable);

    List<Telemetry> findByStatusGreaterThanEqualOrderByTimestampDesc(Integer status, Pageable pageable);

    List<Telemetry> findByApplicationNameAndStatusGreaterThanEqualOrderByTimestampDesc(String applicationName, Integer status, Pageable pageable);

    @Query(value = "SELECT DATE_FORMAT(timestamp, '%Y-%m-%d %H:00:00') as bucket, " +
            "COUNT(*) as requestCount, AVG(duration_ms) as avgDuration, " +
            "SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) as errorCount " +
            "FROM telemetry " +
            "WHERE timestamp >= (NOW() - INTERVAL :hours HOUR) " +
            "AND (:applicationName IS NULL OR application_name = :applicationName) " +
            "GROUP BY bucket ORDER BY bucket ASC", nativeQuery = true)
    List<Object[]> findHourlyTrends(int hours, String applicationName);

    // used by Diagnosis, Query Analyzer, and Index Advisor to only scan recent telemetry
    List<Telemetry> findByTimestampAfter(LocalDateTime timestamp);

    // same as above, scoped to one application — used when the app selector filters
    // Diagnosis/Query Analyzer/Index Advisor to a single app instead of "All Apps"
    List<Telemetry> findByApplicationNameAndTimestampAfter(String applicationName, LocalDateTime timestamp);

    // Historical baseline window — everything between two timestamps, not just
    // "after one." Used by DiagnosisService's PERFORMANCE_REGRESSION check to
    // pull an endpoint's prior history (e.g. the 14 days before the current
    // lookback window) separately from the current window itself, so "is this
    // endpoint currently worse than its own normal" can be measured instead of
    // just "is this endpoint above one fixed number."
    List<Telemetry> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    // same as above, scoped to one application
    List<Telemetry> findByApplicationNameAndTimestampBetween(String applicationName, LocalDateTime start, LocalDateTime end);

    @Modifying
    @Transactional
    @Query("DELETE FROM Telemetry t WHERE t.applicationName = :applicationName")
    int deleteByApplicationName(String applicationName);

    public interface SummaryProjection {
        Long getTotalRequests();
        Double getAverageResponseTime();
        Long getErrorRequests();
        Long getConnectedApplications();
    }
}