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

    // recent requests, most recent first, capped by Pageable limit
    List<Telemetry> findAllByOrderByTimestampDesc(Pageable pageable);

    // error requests, most recent first, capped by Pageable limit
    List<Telemetry> findByStatusGreaterThanEqualOrderByTimestampDesc(Integer status, Pageable pageable);

    // grouped by endpoint, slowest average first
    @Query("SELECT t.endpoint as endpoint, AVG(t.durationMs) as avgDuration, COUNT(t) as count " +
           "FROM Telemetry t GROUP BY t.endpoint ORDER BY AVG(t.durationMs) DESC")
    List<SlowEndpointProjection> findSlowEndpoints(Pageable pageable);

    // hourly trend buckets, last N hours (native query, MySQL syntax)
    @Query(value = "SELECT DATE_FORMAT(timestamp, '%Y-%m-%d %H:00:00') as bucket, " +
            "COUNT(*) as requestCount, AVG(duration_ms) as avgDuration, " +
            "SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) as errorCount " +
            "FROM telemetry " +
            "WHERE timestamp >= (NOW() - INTERVAL :hours HOUR) " +
            "GROUP BY bucket ORDER BY bucket ASC", nativeQuery = true)
    List<Object[]> findHourlyTrends(int hours);

    // used by Diagnosis, Query Analyzer, and Index Advisor to only scan recent telemetry
    List<Telemetry> findByTimestampAfter(LocalDateTime timestamp);

    public interface SlowEndpointProjection {
        String getEndpoint();
        Double getAvgDuration();
        Long getCount();
    }
}