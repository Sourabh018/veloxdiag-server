package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.diagnosis.engine.RuleEngineService;
import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DiagnosisService {

    private double slowRequestThresholdMs = 1000.0;
    private long highErrorRateThreshold = 3;
    private int serverErrorStatusThreshold = 500;
    private long possibleNPlusOneQueryThreshold = 15;
    private long seqScanRowThreshold = 500;
    private static final long MIN_SAMPLE_COUNT = 6;

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;
    private final RuleEngineService ruleEngineService;
    private final SlowQueryPlanRepository slowQueryPlanRepository;

    private static final Pattern SEQ_SCAN_PATTERN = Pattern.compile(
            "Seq Scan on (\\w+)(?:\\s+\\w+)?\\s*\\(cost=[\\d.]+\\.\\.[\\d.]+ rows=(\\d+)"
    );

    public DiagnosisService(TelemetryRepository telemetryRepository, TelemetryWindowSettings windowSettings,
                             RuleEngineService ruleEngineService, SlowQueryPlanRepository slowQueryPlanRepository) {
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
        this.ruleEngineService = ruleEngineService;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
    }

    public double getSlowRequestThresholdMs() { return slowRequestThresholdMs; }
    public void setSlowRequestThresholdMs(double value) { this.slowRequestThresholdMs = value; }

    public long getHighErrorRateThreshold() { return highErrorRateThreshold; }
    public void setHighErrorRateThreshold(long value) { this.highErrorRateThreshold = value; }

    public int getServerErrorStatusThreshold() { return serverErrorStatusThreshold; }
    public void setServerErrorStatusThreshold(int value) { this.serverErrorStatusThreshold = value; }

    public long getPossibleNPlusOneQueryThreshold() { return possibleNPlusOneQueryThreshold; }
    public void setPossibleNPlusOneQueryThreshold(long value) { this.possibleNPlusOneQueryThreshold = value; }

    public long getSeqScanRowThreshold() { return seqScanRowThreshold; }
    public void setSeqScanRowThreshold(long value) { this.seqScanRowThreshold = value; }

    public List<DiagnosisFinding> runDiagnosis() {
        return runDiagnosis(null);
    }

    // App-selector-scoped version. Blank/null applicationName means "All Apps" —
    // same combined behavior as runDiagnosis() above. Filtering happens before
    // grouping by endpoint so cross-app endpoint-name collisions (unlikely, but
    // possible) can't mix findings from two different applications together.
    //
    // PERFORMANCE NOTE (fixed — was an N+1 against our own tables): this method
    // loops over every distinct endpoint. Previously, computeEndpointFindings
    // re-fetched the full rule_definitions table AND re-queried slow_query_plans
    // once PER endpoint — with ~15+ endpoints across apps, every dashboard poll
    // fired 30+ near-identical queries. Both are now fetched ONCE here and
    // passed into the loop, then filtered/grouped in memory per endpoint.
    public List<DiagnosisFinding> runDiagnosis(String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> allTelemetry = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampAfter(cutoff)
                : telemetryRepository.findByApplicationNameAndTimestampAfter(applicationName, cutoff);
        List<DiagnosisFinding> findings = new ArrayList<>();

        Map<String, List<Telemetry>> byEndpoint = allTelemetry.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        // Fetched once for the whole run, not once per endpoint.
        List<com.veloxdiag.server.diagnosis.engine.RuleDefinitionEntity> rules =
                ruleEngineService.loadEnabledRules();

        Map<String, List<SlowQueryPlan>> plansByEndpoint = slowQueryPlanRepository
                .findByContainsSeqScanTrueAndTimestampAfter(cutoff).stream()
                .collect(Collectors.groupingBy(p -> EndpointNormalizer.normalize(p.getEndpoint())));

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<SlowQueryPlan> plansForEndpoint = plansByEndpoint.getOrDefault(endpoint, List.of());
            findings.addAll(computeEndpointFindings(endpoint, entry.getValue(), plansForEndpoint, rules));
        }

        return findings;
    }

    // Batched path — used by runDiagnosis, takes pre-fetched rules and
    // pre-fetched (already endpoint-filtered) seq-scan plans so no query
    // happens inside this method at all.
    private List<DiagnosisFinding> computeEndpointFindings(
            String endpoint, List<Telemetry> records, List<SlowQueryPlan> seqScanPlans,
            List<com.veloxdiag.server.diagnosis.engine.RuleDefinitionEntity> rules) {
        List<DiagnosisFinding> endpointFindings = new ArrayList<>();
        endpointFindings.addAll(checkSlowRequest(endpoint, records));
        endpointFindings.addAll(checkHighErrorRate(endpoint, records));
        endpointFindings.addAll(checkServerErrors(endpoint, records));
        endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records));
        endpointFindings.addAll(checkMissingIndexCandidateFromPlans(endpoint, seqScanPlans));

        List<DiagnosisFinding> combined = new ArrayList<>(endpointFindings);
        combined.addAll(correlateFindings(endpoint, records, endpointFindings));
        combined.addAll(ruleEngineService.evaluate(endpoint, records, rules));

        return combined;
    }

    // Unbatched path — used only by getFindingsForEndpoint, which evaluates a
    // single endpoint on demand (e.g. the "Explain this" narrative fetch).
    // No batching benefit for exactly one endpoint, so this fetches inline as
    // before; kept as a separate method rather than overloading with nulls.
    private List<DiagnosisFinding> computeEndpointFindings(String endpoint, List<Telemetry> records,
                                                             LocalDateTime cutoff) {
        List<DiagnosisFinding> endpointFindings = new ArrayList<>();
        endpointFindings.addAll(checkSlowRequest(endpoint, records));
        endpointFindings.addAll(checkHighErrorRate(endpoint, records));
        endpointFindings.addAll(checkServerErrors(endpoint, records));
        endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records));
        endpointFindings.addAll(checkMissingIndexCandidate(endpoint, cutoff));

        List<DiagnosisFinding> combined = new ArrayList<>(endpointFindings);
        combined.addAll(correlateFindings(endpoint, records, endpointFindings));
        combined.addAll(ruleEngineService.evaluate(endpoint, records));

        return combined;
    }

    public List<DiagnosisFinding> getFindingsForEndpoint(String endpoint) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> records = telemetryRepository.findByTimestampAfter(cutoff).stream()
                .filter(t -> endpoint.equals(EndpointNormalizer.normalize(t.getEndpoint())))
                .collect(Collectors.toList());

        return computeEndpointFindings(endpoint, records, cutoff);
    }

    private List<DiagnosisFinding> checkSlowRequest(String endpoint, List<Telemetry> records) {
        double avgDuration = records.stream()
                .mapToLong(Telemetry::getDurationMs)
                .average()
                .orElse(0.0);

        if (avgDuration > slowRequestThresholdMs) {
            boolean insufficientSamples = records.size() < MIN_SAMPLE_COUNT;
            String severity = insufficientSamples
                    ? "LOW"
                    : (avgDuration > 5000 ? "HIGH" : (avgDuration > 2000 ? "MEDIUM" : "LOW"));

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageDurationMs", avgDuration);
            evidence.put("sampleCount", records.size());
            evidence.put("insufficientSampleSize", insufficientSamples);

            String message = insufficientSamples
                    ? String.format("Endpoint %s is averaging %.0fms per request, above the %.0fms threshold — " +
                                    "but this is based on only %d sample(s), too few to reliably call this a systemic " +
                                    "slowdown rather than a one-off (e.g. cold start).",
                            endpoint, avgDuration, slowRequestThresholdMs, records.size())
                    : String.format("Endpoint %s is averaging %.0fms per request, above the %.0fms threshold.",
                            endpoint, avgDuration, slowRequestThresholdMs);

            return List.of(new DiagnosisFinding("SLOW_REQUEST", severity, endpoint, message, evidence));
        }
        return List.of();
    }

    private List<DiagnosisFinding> checkHighErrorRate(String endpoint, List<Telemetry> records) {
        long errorCount = records.stream()
                .filter(t -> t.getStatus() >= 400)
                .count();

        if (errorCount >= highErrorRateThreshold) {
            String severity = errorCount >= 10 ? "HIGH" : "MEDIUM";
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("errorCount", errorCount);
            evidence.put("totalRequests", records.size());

            return List.of(new DiagnosisFinding(
                    "HIGH_ERROR_RATE",
                    severity,
                    endpoint,
                    String.format("Endpoint %s has %d error responses (4xx/5xx) out of %d total requests.",
                            endpoint, errorCount, records.size()),
                    evidence
            ));
        }
        return List.of();
    }

    private List<DiagnosisFinding> checkServerErrors(String endpoint, List<Telemetry> records) {
        long serverErrorCount = records.stream()
                .filter(t -> t.getStatus() >= serverErrorStatusThreshold)
                .count();

        if (serverErrorCount > 0) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("serverErrorCount", serverErrorCount);

            return List.of(new DiagnosisFinding(
                    "SERVER_ERROR",
                    "HIGH",
                    endpoint,
                    String.format("Endpoint %s returned %d server error(s) (5xx status).",
                            endpoint, serverErrorCount),
                    evidence
            ));
        }
        return List.of();
    }

    private List<DiagnosisFinding> checkPossibleNPlusOne(String endpoint, List<Telemetry> records) {
        List<Long> counts = records.stream()
                .map(Telemetry::getQueryCount)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (counts.isEmpty()) {
            return List.of();
        }

        double avgQueryCount = counts.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long maxQueryCount = counts.stream().mapToLong(Long::longValue).max().orElse(0);

        if (maxQueryCount > possibleNPlusOneQueryThreshold) {
            boolean insufficientSamples = counts.size() < MIN_SAMPLE_COUNT;
            String severity = insufficientSamples
                    ? "LOW"
                    : (maxQueryCount > 50 ? "HIGH" : (maxQueryCount > 25 ? "MEDIUM" : "LOW"));

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageQueryCount", avgQueryCount);
            evidence.put("maxQueryCount", maxQueryCount);
            evidence.put("sampleCount", counts.size());
            evidence.put("insufficientSampleSize", insufficientSamples);

            String message = insufficientSamples
                    ? String.format("Endpoint %s spiked to %d SQL queries in at least one request (average %.1f), " +
                                    "suggesting an N+1 query pattern — but only %d sample(s) with a query count were " +
                                    "observed, too few to confirm this is a recurring pattern rather than a single event.",
                            endpoint, maxQueryCount, avgQueryCount, counts.size())
                    : String.format("Endpoint %s spiked to %d SQL queries in at least one request (average %.1f across %d samples), " +
                                    "suggesting an N+1 query pattern rather than a single efficient fetch.",
                            endpoint, maxQueryCount, avgQueryCount, counts.size());

            return List.of(new DiagnosisFinding("POSSIBLE_N_PLUS_ONE", severity, endpoint, message, evidence));
        }
        return List.of();
    }

    private List<DiagnosisFinding> correlateFindings(String endpoint, List<Telemetry> records,
                                                       List<DiagnosisFinding> endpointFindings) {
        boolean hasSlowRequest = endpointFindings.stream()
                .anyMatch(f -> f.getRuleType().equals("SLOW_REQUEST"));
        boolean hasNPlusOne = endpointFindings.stream()
                .anyMatch(f -> f.getRuleType().equals("POSSIBLE_N_PLUS_ONE"));

        if (!hasSlowRequest || !hasNPlusOne) {
            return List.of();
        }

        List<Telemetry> spikyRecords = records.stream()
                .filter(t -> t.getQueryCount() != null && t.getQueryCount() > possibleNPlusOneQueryThreshold)
                .collect(Collectors.toList());
        List<Telemetry> normalRecords = records.stream()
                .filter(t -> t.getQueryCount() != null && t.getQueryCount() <= possibleNPlusOneQueryThreshold)
                .collect(Collectors.toList());

        OptionalDouble spikyAvgDurationOpt = spikyRecords.stream()
                .mapToLong(Telemetry::getDurationMs).average();
        OptionalDouble normalAvgDurationOpt = normalRecords.stream()
                .mapToLong(Telemetry::getDurationMs).average();

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("spikySampleCount", spikyRecords.size());
        evidence.put("normalSampleCount", normalRecords.size());

        String confidence;
        String message;

        boolean hasReliableBaseline = spikyAvgDurationOpt.isPresent() && normalAvgDurationOpt.isPresent()
                && normalRecords.size() >= MIN_SAMPLE_COUNT;

        if (hasReliableBaseline) {
            double spikyAvg = spikyAvgDurationOpt.getAsDouble();
            double normalAvg = normalAvgDurationOpt.getAsDouble();
            double ratio = normalAvg > 0 ? spikyAvg / normalAvg : 0;

            evidence.put("spikyAvgDurationMs", spikyAvg);
            evidence.put("normalAvgDurationMs", normalAvg);
            evidence.put("durationRatio", ratio);

            if (ratio >= 2.0) {
                confidence = "HIGH";
                message = String.format(
                        "High duration on %s is likely driven by its N+1 query pattern: requests with more than %d queries " +
                                "averaged %.0fms (n=%d) vs %.0fms (n=%d) for requests at or below that threshold — roughly %.1fx slower.",
                        endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            } else if (ratio > 1.0) {
                confidence = "MEDIUM";
                message = String.format(
                        "High duration on %s may be partially driven by its N+1 query pattern: requests with more than %d queries " +
                                "averaged %.0fms (n=%d) vs %.0fms (n=%d) for requests at or below that threshold — roughly %.1fx slower, " +
                                "a modest but directionally consistent difference.",
                        endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            } else {
                confidence = "LOW";
                message = String.format(
                        "Both SLOW_REQUEST and POSSIBLE_N_PLUS_ONE fired for %s, but the N+1 pattern does not appear to be " +
                                "the primary driver of the slowness: requests with more than %d queries averaged %.0fms (n=%d) vs " +
                                "%.0fms (n=%d) for requests at or below that threshold — roughly %.1fx, i.e. no slower (or faster). " +
                                "The two findings likely have separate causes.",
                        endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            }
        } else {
            confidence = "MEDIUM";
            evidence.put("insufficientSampleSize", true);
            message = String.format(
                    "SLOW_REQUEST and POSSIBLE_N_PLUS_ONE both fired for %s, suggesting a link between query volume and " +
                            "duration, but there weren't enough normal-range samples (need >= %d) on this endpoint to measure the exact ratio.",
                    endpoint, MIN_SAMPLE_COUNT);
        }

        return List.of(new DiagnosisFinding(
                "ROOT_CAUSE_CORRELATION",
                confidence,
                endpoint,
                message,
                evidence,
                confidence,
                List.of("SLOW_REQUEST", "POSSIBLE_N_PLUS_ONE")
        ));
    }

    private List<DiagnosisFinding> checkMissingIndexCandidate(String endpoint, LocalDateTime cutoff) {
        List<SlowQueryPlan> plans = slowQueryPlanRepository
                .findByEndpointAndContainsSeqScanTrueAndTimestampAfter(endpoint, cutoff);
        return checkMissingIndexCandidateFromPlans(endpoint, plans);
    }

    // Batched variant — takes plans already fetched (and pre-filtered to this
    // endpoint) by runDiagnosis in one query for all endpoints, instead of
    // querying here. Same logic as checkMissingIndexCandidate above, just
    // without the per-endpoint database round trip.
    private List<DiagnosisFinding> checkMissingIndexCandidateFromPlans(String endpoint, List<SlowQueryPlan> plans) {
        if (plans.isEmpty()) {
            return List.of();
        }

        Map<String, Long> maxRowsPerTable = new HashMap<>();

        for (SlowQueryPlan plan : plans) {
            Matcher matcher = SEQ_SCAN_PATTERN.matcher(plan.getExplainPlan());
            while (matcher.find()) {
                String table = matcher.group(1);
                long rows = Long.parseLong(matcher.group(2));
                maxRowsPerTable.merge(table, rows, Math::max);
            }
        }

        Map<String, Long> candidateTables = maxRowsPerTable.entrySet().stream()
                .filter(e -> e.getValue() > seqScanRowThreshold)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (candidateTables.isEmpty()) {
            return List.of();
        }

        String tableSummary = candidateTables.entrySet().stream()
                .map(e -> e.getKey() + " (~" + e.getValue() + " rows)")
                .collect(Collectors.joining(", "));

        long maxRows = Collections.max(candidateTables.values());
        String severity = maxRows > 10000 ? "HIGH" : (maxRows > 2000 ? "MEDIUM" : "LOW");

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("candidateTables", candidateTables);
        evidence.put("planSampleCount", plans.size());
        evidence.put("rowThreshold", seqScanRowThreshold);

        return List.of(new DiagnosisFinding(
                "MISSING_INDEX_CANDIDATE",
                severity,
                endpoint,
                String.format("Endpoint %s triggers a full table scan (Seq Scan) on: %s. " +
                                "These tables are large enough that an index on the filtered/joined column " +
                                "would likely be faster than scanning every row.",
                        endpoint, tableSummary),
                evidence
        ));
    }
}