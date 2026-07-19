package com.veloxdiag.server.controller;

import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Ingestion endpoint for slow-query EXPLAIN plans, submitted by veloxdiag-starter
 * after a request exceeds the slow-request threshold. Kept separate from the main
 * /api/telemetry endpoint since this is a different, much less frequent kind of
 * payload (only fires for already-slow requests, not every request).
 */
@RestController
@RequestMapping("/api/slow-query-plans")
public class SlowQueryPlanController {

    private final SlowQueryPlanRepository repository;

    public SlowQueryPlanController(SlowQueryPlanRepository repository) {
        this.repository = repository;
    }

    public static class SlowQueryPlanRequest {
        public String applicationName;
        public String endpoint;
        public Long requestDurationMs;
        public String sqlText;
        public String explainPlan;
    }

    @PostMapping
    public SlowQueryPlan submitPlan(@RequestBody SlowQueryPlanRequest request) {
        // "Seq Scan" is the literal string Postgres's EXPLAIN output uses when the
        // planner chose a full table scan over an available index (or chose not to
        // use one). Computed here, not on the starter side, so the parsing logic
        // lives in one place and the starter stays a thin forwarder.
        boolean containsSeqScan = request.explainPlan != null && request.explainPlan.contains("Seq Scan");

        SlowQueryPlan plan = new SlowQueryPlan(
                request.applicationName,
                request.endpoint,
                LocalDateTime.now(),
                request.requestDurationMs,
                request.sqlText,
                request.explainPlan,
                containsSeqScan
        );

        return repository.save(plan);
    }
}