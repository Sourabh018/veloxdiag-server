package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.ConnectionPoolMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ConnectionPoolMetricsRepository extends JpaRepository<ConnectionPoolMetrics, Long> {

    List<ConnectionPoolMetrics> findByApplicationNameAndTimestampAfterOrderByTimestampAsc(
            String applicationName, LocalDateTime cutoff);

    ConnectionPoolMetrics findTopByApplicationNameOrderByTimestampDesc(String applicationName);

    @Modifying
    @Transactional
    @Query("DELETE FROM ConnectionPoolMetrics m WHERE m.applicationName = :applicationName")
    int deleteByApplicationName(String applicationName);
}