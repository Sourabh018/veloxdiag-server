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
    public DashboardSummary getSummary() {
        return dashboardService.getSummary();
    }

    @GetMapping("/api/dashboard/recent")
    public List<Telemetry> getRecent(@RequestParam(defaultValue = "20") int limit) {
        return dashboardService.getRecent(limit);
    }

    @GetMapping("/api/dashboard/errors")
    public List<Telemetry> getErrors(@RequestParam(defaultValue = "20") int limit) {
        return dashboardService.getErrors(limit);
    }

    @GetMapping("/api/dashboard/slow-endpoints")
    public List<SlowEndpointDTO> getSlowEndpoints(@RequestParam(defaultValue = "10") int limit) {
        return dashboardService.getSlowEndpoints(limit);
    }

    @GetMapping("/api/dashboard/trends")
    public List<TrendPointDTO> getTrends(@RequestParam(defaultValue = "24") int hours) {
        return dashboardService.getTrends(hours);
    }
}