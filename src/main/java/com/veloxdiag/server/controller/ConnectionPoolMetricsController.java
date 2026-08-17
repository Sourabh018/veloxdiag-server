package com.veloxdiag.server.controller;

import com.veloxdiag.server.entity.ConnectionPoolMetrics;
import com.veloxdiag.server.service.ConnectionPoolMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/connection-pool-metrics")
public class ConnectionPoolMetricsController {

    private final ConnectionPoolMetricsService connectionPoolMetricsService;

    public ConnectionPoolMetricsController(ConnectionPoolMetricsService connectionPoolMetricsService) {
        this.connectionPoolMetricsService = connectionPoolMetricsService;
    }

    @PostMapping
    public ConnectionPoolMetrics ingest(@Valid @RequestBody ConnectionPoolMetrics metrics) {
        return connectionPoolMetricsService.save(metrics);
    }

    @GetMapping("/history")
    public List<ConnectionPoolMetrics> history(@RequestParam String applicationName) {
        return connectionPoolMetricsService.getHistory(applicationName);
    }

    @GetMapping("/latest")
    public ConnectionPoolMetrics latest(@RequestParam String applicationName) {
        return connectionPoolMetricsService.getLatest(applicationName);
    }
}