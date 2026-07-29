package com.veloxdiag.server.dashboard;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.veloxdiag.server.entity.Telemetry;

@RestController
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/dashboard/summary")
    public DashboardSummary getSummary(@RequestParam(required = false) String applicationName) {
        return dashboardService.getSummary(applicationName);
    }

    @GetMapping("/api/dashboard/recent")
    public List<Telemetry> getRecent(@RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) String applicationName) {
        return dashboardService.getRecent(limit, applicationName);
    }

    @GetMapping("/api/dashboard/errors")
    public List<Telemetry> getErrors(@RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) String applicationName) {
        return dashboardService.getErrors(limit, applicationName);
    }

    @GetMapping("/api/dashboard/slow-endpoints")
    public List<SlowEndpointDTO> getSlowEndpoints(@RequestParam(defaultValue = "10") int limit,
                                                   @RequestParam(required = false) String applicationName) {
        return dashboardService.getSlowEndpoints(limit, applicationName);
    }

    @GetMapping("/api/dashboard/trends")
    public List<TrendPointDTO> getTrends(@RequestParam(defaultValue = "24") int hours,
                                          @RequestParam(required = false) String applicationName) {
        return dashboardService.getTrends(hours, applicationName);
    }

    // Distinct application names seen in telemetry — populates the app-selector
    // dropdown. Frontend adds "All Apps" itself as the default option.
    @GetMapping("/api/dashboard/applications")
    public List<String> getApplications() {
        return dashboardService.getApplications();
    }
}