package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.diagnosis.engine.RuleEngineService;
import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DiagnosisService {

    static final String DEFAULT_KEY = "__default__";

    /**
     * Immutable snapshot of the five rule thresholds for one evaluation run.
     * Resolved once per runDiagnosis(applicationName) call and threaded through
     * every private check method as a parameter, instead of each method reading
     * a shared mutable instance field directly — that's what makes per-application
     * thresholds possible without a data race between concurrent requests for
     * different applications.
     */
    private static class Thresholds {
        final double slowRequestThresholdMs;
        final long highErrorRateThreshold;
        final int serverErrorStatusThreshold;
        final long possibleNPlusOneQueryThreshold;
        final long seqScanRowThreshold;

        Thresholds(double slowRequestThresholdMs, long highErrorRateThreshold, int serverErrorStatusThreshold,
                   long possibleNPlusOneQueryThreshold, long seqScanRowThreshold) {
            this.slowRequestThresholdMs = slowRequestThresholdMs;
            this.highErrorRateThreshold = highErrorRateThreshold;
            this.serverErrorStatusThreshold = serverErrorStatusThreshold;
            this.possibleNPlusOneQueryThreshold = possibleNPlusOneQueryThreshold;
            this.seqScanRowThreshold = seqScanRowThreshold;
        }
    }

    private final Map<String, Thresholds> thresholdsByApp = new ConcurrentHashMap<>();

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
        thresholdsByApp.put(DEFAULT_KEY, new Thresholds(1000.0, 3, 500, 15, 500));
    }

    private static String resolveKey(String applicationName) {
        return (applicationName == null || applicationName.isBlank()) ? DEFAULT_KEY : applicationName;
    }

    private Thresholds resolveThresholds(String applicationName) {
        return thresholdsByApp.getOrDefault(resolveKey(applicationName), thresholdsByApp.get(DEFAULT_KEY));
    }

    // ---- Per-application threshold getters/setters, used by SettingsController ----

    public double getSlowRequestThresholdMs(String applicationName) { return resolveThresholds(applicationName).slowRequestThresholdMs; }
    public long getHighErrorRateThreshold(String applicationName) { return resolveThresholds(applicationName).highErrorRateThreshold; }
    public int getServerErrorStatusThreshold(String applicationName) { return resolveThresholds(applicationName).serverErrorStatusThreshold; }
    public long getPossibleNPlusOneQueryThreshold(String applicationName) { return resolveThresholds(applicationName).possibleNPlusOneQueryThreshold; }
    public long getSeqScanRowThreshold(String applicationName) { return resolveThresholds(applicationName).seqScanRowThreshold; }

    public void setThresholds(String applicationName, double slowRequestThresholdMs, long highErrorRateThreshold,
                               int serverErrorStatusThreshold, long possibleNPlusOneQueryThreshold, long seqScanRowThreshold) {
        thresholdsByApp.put(resolveKey(applicationName), new Thresholds(
                slowRequestThresholdMs, highErrorRateThreshold, serverErrorStatusThreshold,
                possibleNPlusOneQueryThreshold, seqScanRowThreshold
        ));
    }

    // ---- No-arg / single-value overloads, operating on the DEFAULT_KEY bucket ----
    // Kept for any caller not yet passing an applicationName (e.g. buildDefaultFor
    // seed values in SettingsController, or the single-endpoint narrative lookup
    // path which has no application context — see getFindingsForEndpoint below).

    public double getSlowRequestThresholdMs() { return getSlowRequestThresholdMs(null); }
    public void setSlowRequestThresholdMs(double value) {
        Thresholds t = resolveThresholds(null);
        setThresholds(null, value, t.highErrorRateThreshold, t.serverErrorStatusThreshold, t.possibleNPlusOneQueryThreshold, t.seqScanRowThreshold);
    }

    public long getHighErrorRateThreshold() { return getHighErrorRateThreshold(null); }
    public void setHighErrorRateThreshold(long value) {
        Thresholds t = resolveThresholds(null);
        setThresholds(null, t.slowRequestThresholdMs, value, t.serverErrorStatusThreshold, t.possibleNPlusOneQueryThreshold, t.seqScanRowThreshold);
    }

    public int getServerErrorStatusThreshold() { return getServerErrorStatusThreshold(null); }
    public void setServerErrorStatusThreshold(int value) {
        Thresholds t = resolveThresholds(null);
        setThresholds(null, t.slowRequestThresholdMs, t.highErrorRateThreshold, value, t.possibleNPlusOneQueryThreshold, t.seqScanRowThreshold);
    }

    public long getPossibleNPlusOneQueryThreshold() { return getPossibleNPlusOneQueryThreshold(null); }
    public void setPossibleNPlusOneQueryThreshold(long value) {
        Thresholds t = resolveThresholds(null);
        setThresholds(null, t.slowRequestThresholdMs, t.highErrorRateThreshold, t.serverErrorStatusThreshold, value, t.seqScanRowThreshold);
    }

    public long getSeqScanRowThreshold() { return getSeqScanRowThreshold(null); }
    public void setSeqScanRowThreshold(long value) {
        Thresholds t = resolveThresholds(null);
        setThresholds(null, t.slowRequestThresholdMs, t.highErrorRateThreshold, t.serverErrorStatusThreshold, t.possibleNPlusOneQueryThreshold, value);
    }

    public List<DiagnosisFinding> runDiagnosis() {
        return runDiagnosis(null);
    }

    // App-selector-scoped version. Blank/null applicationName means "All Apps" —
    // same combined behavior as runDiagnosis() above. Filtering happens before
    // grouping by endpoint so cross-app endpoint-name collisions (unlikely, but
    // possible) can't mix findings from two different applications together.
    // Thresholds are now resolved once here for the specific application being
    // diagnosed (falling back to DEFAULT_KEY for "All Apps"), instead of reading
    // one shared mutable field — this is what makes per-app threshold tuning
    // actually affect evaluation results, not just the Settings page display.
    public List<DiagnosisFinding> runDiagnosis(String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays(applicationName));
        Thresholds thresholds = resolveThresholds(applicationName);
        List<Telemetry> allTelemetry = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampAfter(cutoff)
                : telemetryRepository.findByApplicationNameAndTimestampAfter(applicationName, cutoff);
        List<DiagnosisFinding> findings = new ArrayList<>();

        Map<String, List<Telemetry>> byEndpoint = allTelemetry.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        List<com.veloxdiag.server.diagnosis.engine.RuleDefinitionEntity> rules =
                ruleEngineService.loadEnabledRules();

        Map<String, List<SlowQueryPlan>> plansByEndpoint = slowQueryPlanRepository
                .findByContainsSeqScanTrueAndTimestampAfter(cutoff).stream()
                .collect(Collectors.groupingBy(p -> EndpointNormalizer.normalize(p.getEndpoint())));

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<SlowQueryPlan> plansForEndpoint = plansByEndpoint.getOrDefault(endpoint, List.of());
            findings.addAll(computeEndpointFindings(endpoint, entry.getValue(), plansForEndpoint, rules, thresholds));
        }

        return findings;
    }

    // Batched path — used by runDiagnosis, takes pre-fetched rules, pre-fetched
    // (already endpoint-filtered) seq-scan plans, and the resolved thresholds
    // for the application being diagnosed.
    private List<DiagnosisFinding> computeEndpointFindings(
            String endpoint, List<Telemetry> records, List<SlowQueryPlan> seqScanPlans,
            List<com.veloxdiag.server.diagnosis.engine.RuleDefinitionEntity> rules, Thresholds thresholds) {
        List<DiagnosisFinding> endpointFindings = new ArrayList<>();
        endpointFindings.addAll(checkSlowRequest(endpoint, records, thresholds));
        endpointFindings.addAll(checkHighErrorRate(endpoint, records, thresholds));
        endpointFindings.addAll(checkServerErrors(endpoint, records, thresholds));
        endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records, thresholds));
        endpointFindings.addAll(checkMissingIndexCandidateFromPlans(endpoint, seqScanPlans, thresholds));

        List<DiagnosisFinding> combined = new ArrayList<>(endpointFindings);
        combined.addAll(correlateFindings(endpoint, records, endpointFindings, thresholds));
        combined.addAll(ruleEngineService.evaluate(endpoint, records, rules));

        return combined;
    }

    // Unbatched path — used only by getFindingsForEndpoint, which evaluates a
    // single endpoint on demand (e.g. the "Explain this" narrative fetch). This
    // path has no application context (endpoint names are looked up without
    // knowing which app they belong to), so it uses the DEFAULT_KEY thresholds.
    // Known limitation: if the same endpoint name exists in two different apps
    // with different tuned thresholds, this path won't pick the right one —
    // narrow gap, only affects the single-endpoint narrative/explain feature,
    // not the main Diagnosis/Dashboard/Index Advisor views.
    private List<DiagnosisFinding> computeEndpointFindings(String endpoint, List<Telemetry> records,
                                                             LocalDateTime cutoff) {
        Thresholds thresholds = resolveThresholds(null);
        List<DiagnosisFinding> endpointFindings = new ArrayList<>();
        endpointFindings.addAll(checkSlowRequest(endpoint, records, thresholds));
        endpointFindings.addAll(checkHighErrorRate(endpoint, records, thresholds));
        endpointFindings.addAll(checkServerErrors(endpoint, records, thresholds));
        endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records, thresholds));
        endpointFindings.addAll(checkMissingIndexCandidate(endpoint, cutoff, thresholds));

        List<DiagnosisFinding> combined = new ArrayList<>(endpointFindings);
        combined.addAll(correlateFindings(endpoint, records, endpointFindings, thresholds));
        combined.addAll(ruleEngineService.evaluate(endpoint, records));

        return combined;
    }

    public List<DiagnosisFinding> getFindingsForEndpoint(String endpoint) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays(null));
        List<Telemetry> records = telemetryRepository.findByTimestampAfter(cutoff).stream()
                .filter(t -> endpoint.equals(EndpointNormalizer.normalize(t.getEndpoint())))
                .collect(Collectors.toList());

        return computeEndpointFindings(endpoint, records, cutoff);
    }

    private List<DiagnosisFinding> checkSlowRequest(String endpoint, List<Telemetry> records, Thresholds thresholds) {
        double avgDuration = records.stream()
                .mapToLong(Telemetry::getDurationMs)
                .average()
                .orElse(0.0);

        if (avgDuration > thresholds.slowRequestThresholdMs) {
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
                            endpoint, avgDuration, thresholds.slowRequestThresholdMs, records.size())
                    : String.format("Endpoint %s is averaging %.0fms per request, above the %.0fms threshold.",
                            endpoint, avgDuration, thresholds.slowRequestThresholdMs);

            return List.of(new DiagnosisFinding("SLOW_REQUEST", severity, endpoint, message, evidence));
        }
        return List.of();
    }

    private List<DiagnosisFinding> checkHighErrorRate(String endpoint, List<Telemetry> records, Thresholds thresholds) {
        long errorCount = records.stream()
                .filter(t -> t.getStatus() >= 400)
                .count();

        if (errorCount >= thresholds.highErrorRateThreshold) {
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

    private List<DiagnosisFinding> checkServerErrors(String endpoint, List<Telemetry> records, Thresholds thresholds) {
        long serverErrorCount = records.stream()
                .filter(t -> t.getStatus() >= thresholds.serverErrorStatusThreshold)
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

    private List<DiagnosisFinding> checkPossibleNPlusOne(String endpoint, List<Telemetry> records, Thresholds thresholds) {
        List<Long> counts = records.stream()
                .map(Telemetry::getQueryCount)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (counts.isEmpty()) {
            return List.of();
        }

        double avgQueryCount = counts.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long maxQueryCount = counts.stream().mapToLong(Long::longValue).max().orElse(0);

        if (maxQueryCount > thresholds.possibleNPlusOneQueryThreshold) {
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
                                                       List<DiagnosisFinding> endpointFindings, Thresholds thresholds) {
        boolean hasSlowRequest = endpointFindings.stream()
                .anyMatch(f -> f.getRuleType().equals("SLOW_REQUEST"));
        boolean hasNPlusOne = endpointFindings.stream()
                .anyMatch(f -> f.getRuleType().equals("POSSIBLE_N_PLUS_ONE"));

        if (!hasSlowRequest || !hasNPlusOne) {
            return List.of();
        }

        List<Telemetry> spikyRecords = records.stream()
                .filter(t -> t.getQueryCount() != null && t.getQueryCount() > thresholds.possibleNPlusOneQueryThreshold)
                .collect(Collectors.toList());
        List<Telemetry> normalRecords = records.stream()
                .filter(t -> t.getQueryCount() != null && t.getQueryCount() <= thresholds.possibleNPlusOneQueryThreshold)
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
                        endpoint, thresholds.possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            } else if (ratio > 1.0) {
                confidence = "MEDIUM";
                message = String.format(
                        "High duration on %s may be partially driven by its N+1 query pattern: requests with more than %d queries " +
                                "averaged %.0fms (n=%d) vs %.0fms (n=%d) for requests at or below that threshold — roughly %.1fx slower, " +
                                "a modest but directionally consistent difference.",
                        endpoint, thresholds.possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            } else {
                confidence = "LOW";
                message = String.format(
                        "Both SLOW_REQUEST and POSSIBLE_N_PLUS_ONE fired for %s, but the N+1 pattern does not appear to be " +
                                "the primary driver of the slowness: requests with more than %d queries averaged %.0fms (n=%d) vs " +
                                "%.0fms (n=%d) for requests at or below that threshold — roughly %.1fx, i.e. no slower (or faster). " +
                                "The two findings likely have separate causes.",
                        endpoint, thresholds.possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
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

    private List<DiagnosisFinding> checkMissingIndexCandidate(String endpoint, LocalDateTime cutoff, Thresholds thresholds) {
        List<SlowQueryPlan> plans = slowQueryPlanRepository
                .findByEndpointAndContainsSeqScanTrueAndTimestampAfter(endpoint, cutoff);
        return checkMissingIndexCandidateFromPlans(endpoint, plans, thresholds);
    }

    // Batched variant — takes plans already fetched (and pre-filtered to this
    // endpoint) by runDiagnosis in one query for all endpoints, instead of
    // querying here. Same logic as checkMissingIndexCandidate above, just
    // without the per-endpoint database round trip.
    private List<DiagnosisFinding> checkMissingIndexCandidateFromPlans(String endpoint, List<SlowQueryPlan> plans, Thresholds thresholds) {
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
                .filter(e -> e.getValue() > thresholds.seqScanRowThreshold)
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
        evidence.put("rowThreshold", thresholds.seqScanRowThreshold);

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