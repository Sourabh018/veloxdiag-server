package com.veloxdiag.server.dashboard;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.veloxdiag.server.entity.Telemetry;

@RestController
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardSummaryService dashboardSummaryService;

    public DashboardController(DashboardService dashboardService,
                                DashboardSummaryService dashboardSummaryService) {
        this.dashboardService = dashboardService;
        this.dashboardSummaryService = dashboardSummaryService;
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

    // AI wow feature #2 — lazy-loaded on click from frontend, not auto-fetched
    // on page load. See DashboardSummaryService for prompt/logic.
    @GetMapping("/api/dashboard/ai-summary")
    public Map<String, String> getAiSummary(@RequestParam(required = false) String applicationName) {
        return dashboardSummaryService.generateSummary(applicationName);
    }
}