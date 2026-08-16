package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.JvmMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface JvmMetricsRepository extends JpaRepository<JvmMetrics, Long> {

    // Recent history for the JVM/GC dashboard chart, one application.
    List<JvmMetrics> findByApplicationNameAndTimestampAfterOrderByTimestampAsc(
            String applicationName, LocalDateTime cutoff);

    // Most recent single reading — used for the dashboard's current
    // heap/GC summary tile, cheaper than pulling the whole window just to
    // read the last row.
    JvmMetrics findTopByApplicationNameOrderByTimestampDesc(String applicationName);

    // Paired with TelemetryRepository.deleteByApplicationName /
    // SlowQueryPlanRepository.deleteByApplicationName — same Settings-page
    // reset pattern, so a reset clears JVM history too, not just request
    // telemetry and query plans.
    @Modifying
    @Transactional
    @Query("DELETE FROM JvmMetrics m WHERE m.applicationName = :applicationName")
    int deleteByApplicationName(String applicationName);
}