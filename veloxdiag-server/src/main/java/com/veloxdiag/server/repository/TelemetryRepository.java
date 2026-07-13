package com.veloxdiag.server.repository;

import com.veloxdiag.server.entity.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    @Query("SELECT COUNT(t) FROM Telemetry t")
    long getTotalRequests();

    @Query("SELECT AVG(t.durationMs) FROM Telemetry t")
    Double getAverageResponseTime();

    @Query("SELECT COUNT(t) FROM Telemetry t WHERE t.status >= 400")
    long getErrorRequests();

    @Query("SELECT COUNT(DISTINCT t.applicationName) FROM Telemetry t")
    long getConnectedApplications();

}