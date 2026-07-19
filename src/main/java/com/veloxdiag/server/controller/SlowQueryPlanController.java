package com.veloxdiag.server.controller;

import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/slow-query-plans")
public class SlowQueryPlanController {

    @Autowired
    private SlowQueryPlanRepository slowQueryPlanRepository;

    // ... existing POST ingestion endpoint stays as-is above/below this ...

    // Dashboard-facing: returns the most recent captured EXPLAIN plans for an
    // endpoint, used by the "show query plan" expand action on a
    // MISSING_INDEX_CANDIDATE finding card. Not filtered to seq-scan-only —
    // shows whatever was actually captured so the user sees real evidence,
    // including cases where the plan turned out fine.
    @GetMapping
    public List<SlowQueryPlan> getRecentPlans(@RequestParam String endpoint) {
        return slowQueryPlanRepository.findTop3ByEndpointOrderByTimestampDesc(endpoint);
    }
}