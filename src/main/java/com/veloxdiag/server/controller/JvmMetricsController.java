package com.veloxdiag.server.controller;

import com.veloxdiag.server.entity.JvmMetrics;
import com.veloxdiag.server.service.JvmMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Phase 2.1 — receives periodic JVM/GC reports from veloxdiag-starter (see
 * JvmMetrics javadoc for the full capture-vs-store split). POST is guarded
 * by the same X-API-KEY mechanism as /api/telemetry and
 * /api/slow-query-plans — see TelemetryIngestFilter, which needs
 * "/api/jvm-metrics" added to its isIngestPath check for this to actually
 * be enforced (done alongside this controller, not a separate step).
 */
@RestController
@RequestMapping("/api/jvm-metrics")
public class JvmMetricsController {

    private final JvmMetricsService jvmMetricsService;

    public JvmMetricsController(JvmMetricsService jvmMetricsService) {
        this.jvmMetricsService = jvmMetricsService;
    }

    @PostMapping
    public JvmMetrics ingest(@Valid @RequestBody JvmMetrics metrics) {
        return jvmMetricsService.save(metrics);
    }

    @GetMapping("/history")
    public List<JvmMetrics> history(@RequestParam String applicationName) {
        return jvmMetricsService.getHistory(applicationName);
    }

    @GetMapping("/latest")
    public JvmMetrics latest(@RequestParam String applicationName) {
        return jvmMetricsService.getLatest(applicationName);
    }
}