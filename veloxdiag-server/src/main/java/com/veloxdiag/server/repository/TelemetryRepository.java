package com.veloxdiag.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.veloxdiag.server.entity.Telemetry;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

}