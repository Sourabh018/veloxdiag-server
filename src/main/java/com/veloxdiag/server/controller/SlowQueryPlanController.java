package com.veloxdiag.server.controller;

import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/slow-query-plans")
public class SlowQueryPlanController {

    // Cheap, honest signal extracted at ingestion time — same "Seq Scan on X"
    // shape DiagnosisService.SEQ_SCAN_PATTERN parses later. Kept intentionally
    // simple here (existence check only) since the row-count/table extraction
    // happens downstream in DiagnosisService; this just needs a boolean to
    // filter on.
    private static final Pattern SEQ_SCAN_CHECK = Pattern.compile("Seq Scan on");

    @Autowired
    private SlowQueryPlanRepository slowQueryPlanRepository;

    // Ingestion endpoint — receives one captured EXPLAIN plan per slow
    // SELECT statement from veloxdiag-starter's SlowQueryExplainCapture
    // (via TelemetryClient.sendSlowQueryPlan). Computes containsSeqScan at
    // write time so DiagnosisService.checkMissingIndexCandidate() can filter
    // on it directly without re-parsing explainPlan text on every diagnosis run.
    @PostMapping
    public SlowQueryPlan ingestPlan(@RequestBody SlowQueryPlanRequest request) {
        boolean containsSeqScan = request.getExplainPlan() != null
                && SEQ_SCAN_CHECK.matcher(request.getExplainPlan()).find();

        SlowQueryPlan plan = new SlowQueryPlan(
                request.getApplicationName(),
                request.getEndpoint(),
                LocalDateTime.now(),
                request.getRequestDurationMs(),
                request.getSqlText(),
                request.getExplainPlan(),
                containsSeqScan
        );

        return slowQueryPlanRepository.save(plan);
    }

    // Dashboard-facing: returns the most recent captured EXPLAIN plans for an
    // endpoint, used by the "show query plan" expand action on a
    // MISSING_INDEX_CANDIDATE finding card. Not filtered to seq-scan-only —
    // shows whatever was actually captured so the user sees real evidence,
    // including cases where the plan turned out fine.
    @GetMapping
    public List<SlowQueryPlan> getRecentPlans(@RequestParam String endpoint) {
        return slowQueryPlanRepository.findTop3ByEndpointOrderByTimestampDesc(endpoint);
    }

    // Server-side mirror of veloxdiag-starter's SlowQueryPlanRequest — field
    // names must match exactly since RestTemplate serializes directly to JSON.
    public static class SlowQueryPlanRequest {
        private String applicationName;
        private String endpoint;
        private Long requestDurationMs;
        private String sqlText;
        private String explainPlan;

        public String getApplicationName() { return applicationName; }
        public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public Long getRequestDurationMs() { return requestDurationMs; }
        public void setRequestDurationMs(Long requestDurationMs) { this.requestDurationMs = requestDurationMs; }

        public String getSqlText() { return sqlText; }
        public void setSqlText(String sqlText) { this.sqlText = sqlText; }

        public String getExplainPlan() { return explainPlan; }
        public void setExplainPlan(String explainPlan) { this.explainPlan = explainPlan; }
    }
}