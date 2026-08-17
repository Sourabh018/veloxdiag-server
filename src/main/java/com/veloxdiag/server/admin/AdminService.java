package com.veloxdiag.server.admin;

import com.veloxdiag.server.auth.ApplicationRepository;
import com.veloxdiag.server.repository.ConnectionPoolMetricsRepository;
import com.veloxdiag.server.repository.JvmMetricsRepository;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminService {

    private final TelemetryRepository telemetryRepository;
    private final SlowQueryPlanRepository slowQueryPlanRepository;
    private final ApplicationRepository applicationRepository;
    private final JvmMetricsRepository jvmMetricsRepository;
    private final ConnectionPoolMetricsRepository connectionPoolMetricsRepository;

    public AdminService(TelemetryRepository telemetryRepository,
                         SlowQueryPlanRepository slowQueryPlanRepository,
                         ApplicationRepository applicationRepository,
                         JvmMetricsRepository jvmMetricsRepository,
                         ConnectionPoolMetricsRepository connectionPoolMetricsRepository) {
        this.telemetryRepository = telemetryRepository;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
        this.applicationRepository = applicationRepository;
        this.jvmMetricsRepository = jvmMetricsRepository;
        this.connectionPoolMetricsRepository = connectionPoolMetricsRepository;
    }

    public AdminResetResult resetApplication(String applicationName) {
        int telemetryDeleted = telemetryRepository.deleteByApplicationName(applicationName);
        int plansDeleted = slowQueryPlanRepository.deleteByApplicationName(applicationName);
        jvmMetricsRepository.deleteByApplicationName(applicationName);
        connectionPoolMetricsRepository.deleteByApplicationName(applicationName);
        return new AdminResetResult(applicationName, telemetryDeleted, plansDeleted);
    }

    public record AdminResetResult(String applicationName, int telemetryRowsDeleted, int slowQueryPlanRowsDeleted) {}

    public int wipeAllApplications() {
        long count = applicationRepository.count();
        applicationRepository.deleteAll();
        return (int) count;
    }
}