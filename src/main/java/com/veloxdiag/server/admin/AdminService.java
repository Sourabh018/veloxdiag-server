package com.veloxdiag.server.admin;

import com.veloxdiag.server.auth.ApplicationRepository;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminService {

    private final TelemetryRepository telemetryRepository;
    private final SlowQueryPlanRepository slowQueryPlanRepository;
    private final ApplicationRepository applicationRepository;

    public AdminService(TelemetryRepository telemetryRepository,
                         SlowQueryPlanRepository slowQueryPlanRepository,
                         ApplicationRepository applicationRepository) {
        this.telemetryRepository = telemetryRepository;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
        this.applicationRepository = applicationRepository;
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

    // Wipes every row in the Application table — every registration, every
    // owner, every ingest key — across ALL users, not scoped to one caller
    // like ApplicationController.deleteApplication is. Does NOT touch
    // Telemetry/SlowQueryPlan (real request data is untouched; this only
    // clears the "who registered/owns what name" ledger sitting on top of
    // it), and does NOT touch the User table (accounts/logins survive).
    // One-shot recovery tool for dev/test cleanup — not something a normal
    // dashboard flow should ever call.
    public int wipeAllApplications() {
        long count = applicationRepository.count();
        applicationRepository.deleteAll();
        return (int) count;
    }
}