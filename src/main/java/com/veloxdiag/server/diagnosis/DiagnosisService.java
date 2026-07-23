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

    // Now mutable — adjustable at runtime via Settings, defaults match original hardcoded values
    private double slowRequestThresholdMs = 1000.0;
    private long highErrorRateThreshold = 3;
    private int serverErrorStatusThreshold = 500;
    private long possibleNPlusOneQueryThreshold = 15;

    // A Seq Scan alone isn't a problem — Postgres correctly prefers a full scan
    // over an index on small tables (confirmed in this project: exams/subjects/
    // topics all show Seq Scan at 2-77 rows, which is the planner making the
    // right call, not a missing index). Only flag tables where the row estimate
    // exceeds this floor, so tiny lookup tables don't generate noisy findings.
    private long seqScanRowThreshold = 500;

    // Addendum v1.2 / Section 10.1: mirrors IndexAdvisorService's MIN_SAMPLE_COUNT.
    // Below this many samples, a HIGH/MEDIUM severity finding overstates the
    // confidence the underlying data actually supports (e.g. a HIGH Slow Request
    // off 2 samples could just be a cold-start fluke). We don't suppress the
    // finding — the signal may still be real — we downgrade severity to LOW and
    // annotate the message and evidence so a reader isn't misled about confidence.
    private static final long MIN_SAMPLE_COUNT = 6;

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;
    private final RuleEngineService ruleEngineService;
    private final SlowQueryPlanRepository slowQueryPlanRepository;

    // Matches lines like "Seq Scan on exam_questions eq1_0  (cost=0.00..85.02 rows=3202 width=97)"
    // and also the no-alias form "Seq Scan on exam_questions  (cost=... rows=3202 ...)".
    // Captures group(1)=table name, group(2)=row estimate.
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
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> allTelemetry = telemetryRepository.findByTimestampAfter(cutoff);
        List<DiagnosisFinding> findings = new ArrayList<>();

        Map<String, List<Telemetry>> byEndpoint = allTelemetry.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<Telemetry> records = entry.getValue();

            List<DiagnosisFinding> endpointFindings = new ArrayList<>();
            endpointFindings.addAll(checkSlowRequest(endpoint, records));
            endpointFindings.addAll(checkHighErrorRate(endpoint, records));
            endpointFindings.addAll(checkServerErrors(endpoint, records));
            endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records));
            endpointFindings.addAll(checkMissingIndexCandidate(endpoint, cutoff));

            findings.addAll(endpointFindings);
            // Correlation runs after the individual checks for this endpoint, since it
            // needs to know which finding types already fired here before it decides
            // whether a root-cause link between them is worth surfacing.
            findings.addAll(correlateFindings(endpoint, records, endpointFindings));

            // Data-driven rules, loaded from rule_definitions, run last. These are
            // fully independent of the hardcoded checks above — a rule stored in the
            // DB can fire, be edited, or be added without touching this file at all.
            findings.addAll(ruleEngineService.evaluate(endpoint, records));
        }

        return findings;
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
        // Older Starter versions / non-JPA apps send queryCount=null — ignore those records
        List<Long> counts = records.stream()
                .map(Telemetry::getQueryCount)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (counts.isEmpty()) {
            return List.of();
        }

        double avgQueryCount = counts.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long maxQueryCount = counts.stream().mapToLong(Long::longValue).max().orElse(0);

        // Flag on MAX, not average — N+1 patterns are often spiky/data-dependent
        // (e.g. more rows returned -> more per-row queries), so a handful of
        // cheap requests can mask a genuine N+1 spike if we only look at the mean.
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

    /**
     * Root Cause Correlation — first rule: links SLOW_REQUEST and POSSIBLE_N_PLUS_ONE
     * on the same endpoint, but only when we can actually show the duration difference
     * between "spiky" (high query count) and "normal" requests for that endpoint —
     * not just that both findings happened to fire.
     *
     * Confidence is derived from the real ratio between the two groups' average
     * durations, mirroring the manual analysis that first validated this correlation
     * (26-query requests running ~5-15x slower than ~6-query requests on the same
     * endpoint). We deliberately avoid inventing a numeric percentage — a HIGH/MEDIUM/LOW
     * label backed by an actual measured ratio is honest; a fabricated "87% confidence"
     * would not be.
     *
     * Addendum v1.2 / Section 10.2: the ratio branch used to be binary (>=2.0 -> HIGH,
     * else MEDIUM), which meant a ratio at or below 1.0 — spiky requests actually
     * FASTER than normal ones, i.e. data pointing the opposite direction from the
     * claim — still got a MEDIUM-confidence "is likely driven by" message. That's a
     * self-contradictory finding. This is now a three-way branch so the label and
     * wording track the direction of the evidence, not just whether both findings fired.
     */
    private List<DiagnosisFinding> correlateFindings(String endpoint, List<Telemetry> records,
                                                       List<DiagnosisFinding> endpointFindings) {
        boolean hasSlowRequest = endpointFindings.stream()
                .anyMatch(f -> f.getRuleType().equals("SLOW_REQUEST"));
        boolean hasNPlusOne = endpointFindings.stream()
                .anyMatch(f -> f.getRuleType().equals("POSSIBLE_N_PLUS_ONE"));

        if (!hasSlowRequest || !hasNPlusOne) {
            return List.of();
        }

        // Split this endpoint's records into "spiky" (query count above the N+1
        // threshold) vs "normal" (query count present but at/below threshold).
        // Records with a null queryCount (older Starter versions / non-JPA apps)
        // are excluded from both groups rather than guessed at.
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

        // Addendum v1.2 / Section 10.1: require a real baseline before trusting the
        // ratio at all — mirrors MIN_SAMPLE_COUNT used elsewhere in this file, not
        // the old ad-hoc ">= 3" spot-check.
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
                // Spiky requests are at least 2x slower than normal ones on the same
                // endpoint — a real, measured difference, not just co-occurrence.
                confidence = "HIGH";
                message = String.format(
                        "High duration on %s is likely driven by its N+1 query pattern: requests with more than %d queries " +
                                "averaged %.0fms (n=%d) vs %.0fms (n=%d) for requests at or below that threshold — roughly %.1fx slower.",
                        endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            } else if (ratio > 1.0) {
                // Directionally consistent (spiky requests are slower) but the gap is
                // modest — a weaker, still honest claim than "is likely driven by".
                confidence = "MEDIUM";
                message = String.format(
                        "High duration on %s may be partially driven by its N+1 query pattern: requests with more than %d queries " +
                                "averaged %.0fms (n=%d) vs %.0fms (n=%d) for requests at or below that threshold — roughly %.1fx slower, " +
                                "a modest but directionally consistent difference.",
                        endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            } else {
                // ratio <= 1.0: spiky requests were AT OR BELOW normal duration —
                // the data points the opposite way from a causal link. Report this
                // honestly rather than asserting a link the numbers contradict.
                confidence = "LOW";
                message = String.format(
                        "Both SLOW_REQUEST and POSSIBLE_N_PLUS_ONE fired for %s, but the N+1 pattern does not appear to be " +
                                "the primary driver of the slowness: requests with more than %d queries averaged %.0fms (n=%d) vs " +
                                "%.0fms (n=%d) for requests at or below that threshold — roughly %.1fx, i.e. no slower (or faster). " +
                                "The two findings likely have separate causes.",
                        endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
            }
        } else {
            // Both findings fired, but we don't have enough of a baseline (e.g. every
            // sample was a spike, or too few normal-range samples) to measure a ratio.
            // Still worth surfacing the correlation, just with lower confidence and an
            // honest note about why.
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

    /**
     * Reads EXPLAIN plans captured for this endpoint's slow requests (see
     * SlowQueryExplainCapture in veloxdiag-starter) and flags tables that got
     * a Seq Scan AND have a row estimate above seqScanRowThreshold.
     *
     * The row-count floor is the important part: a Seq Scan by itself is not
     * evidence of a missing index. Postgres correctly prefers a full scan over
     * an index on small tables — confirmed directly in this project, where
     * exams/subjects/topics all showed Seq Scan at 2-77 rows, which is the
     * planner making the right call. Only exam_questions (3202 rows) getting a
     * Seq Scan is a genuine candidate. Without this floor, this rule would
     * mostly generate noise about tables that don't actually need an index.
     */
    private List<DiagnosisFinding> checkMissingIndexCandidate(String endpoint, LocalDateTime cutoff) {
        List<SlowQueryPlan> plans = slowQueryPlanRepository
                .findByEndpointAndContainsSeqScanTrueAndTimestampAfter(endpoint, cutoff);

        if (plans.isEmpty()) {
            return List.of();
        }

        // Track the largest row estimate seen per table across all plans for
        // this endpoint, since the same table may appear with slightly
        // different estimates across different captured requests.
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