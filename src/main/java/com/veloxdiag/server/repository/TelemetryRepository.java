package com.veloxdiag.server.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    // Combines the 4 queries above into 1 — used by getSummary() to avoid firing
    // 4 separate round-trips on every dashboard poll.
    @Query("SELECT COUNT(t) as totalRequests, " +
           "AVG(t.durationMs) as averageResponseTime, " +
           "SUM(CASE WHEN t.status >= 400 THEN 1 ELSE 0 END) as errorRequests, " +
           "COUNT(DISTINCT t.applicationName) as connectedApplications " +
           "FROM Telemetry t")
    SummaryProjection getSummaryStats();

    // Same as getSummaryStats() but scoped to one application — used when the
    // dashboard's app selector has a specific app chosen instead of "All Apps".
    @Query("SELECT COUNT(t) as totalRequests, " +
           "AVG(t.durationMs) as averageResponseTime, " +
           "SUM(CASE WHEN t.status >= 400 THEN 1 ELSE 0 END) as errorRequests, " +
           "COUNT(DISTINCT t.applicationName) as connectedApplications " +
           "FROM Telemetry t WHERE t.applicationName = :applicationName")
    SummaryProjection getSummaryStatsByApplication(String applicationName);

    // Distinct app names seen in telemetry — populates the app-selector dropdown.
    @Query("SELECT DISTINCT t.applicationName FROM Telemetry t ORDER BY t.applicationName")
    List<String> findDistinctApplicationNames();

    // recent requests, most recent first, capped by Pageable limit
    List<Telemetry> findAllByOrderByTimestampDesc(Pageable pageable);

    // same as above, scoped to one application
    List<Telemetry> findByApplicationNameOrderByTimestampDesc(String applicationName, Pageable pageable);

    // error requests, most recent first, capped by Pageable limit
    List<Telemetry> findByStatusGreaterThanEqualOrderByTimestampDesc(Integer status, Pageable pageable);

    // same as above, scoped to one application
    List<Telemetry> findByApplicationNameAndStatusGreaterThanEqualOrderByTimestampDesc(String applicationName, Integer status, Pageable pageable);

    // hourly trend buckets, last N hours (native query, MySQL syntax). applicationName
    // is nullable — passing null matches all applications (the "All Apps" view).
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

    public interface SummaryProjection {
        Long getTotalRequests();
        Double getAverageResponseTime();
        Long getErrorRequests();
        Long getConnectedApplications();
    }
}