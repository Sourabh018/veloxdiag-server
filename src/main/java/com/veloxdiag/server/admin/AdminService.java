package com.veloxdiag.server.admin;

import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminService {

    private final TelemetryRepository telemetryRepository;
    private final SlowQueryPlanRepository slowQueryPlanRepository;

    public AdminService(TelemetryRepository telemetryRepository,
                         SlowQueryPlanRepository slowQueryPlanRepository) {
        this.telemetryRepository = telemetryRepository;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
    }

    // Clears both telemetry and slow_query_plans for one application, scoped
    // so a reset never touches another monitored app's data. Deliberately
    // does NOT touch rule_definitions (shared config, not per-app data) or
    // any other application's rows.
    public AdminResetResult resetApplication(String applicationName) {
        int telemetryDeleted = telemetryRepository.deleteByApplicationName(applicationName);
        int plansDeleted = slowQueryPlanRepository.deleteByApplicationName(applicationName);
        return new AdminResetResult(applicationName, telemetryDeleted, plansDeleted);
    }

    public record AdminResetResult(String applicationName, int telemetryRowsDeleted, int slowQueryPlanRowsDeleted) {}
}