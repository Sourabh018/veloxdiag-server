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

    // Baseline window for PERFORMANCE_REGRESSION: how far back to look for an
    // endpoint's own historical normal, and how many samples that history
    // needs before we trust it enough to compute a baseline from it at all.
    // Deliberately higher than MIN_SAMPLE_COUNT (6) — a baseline is a claim
    // about "normal," not just "enough to mention," so it earns a stricter bar.
    private static final long BASELINE_LOOKBACK_DAYS = 2;
    private static final long MIN_BASELINE_SAMPLE_COUNT = 15;
    // z-score above which current performance is flagged as a regression
    // relative to the endpoint's own baseline (not a global constant).
    private static final double REGRESSION_Z_SCORE_THRESHOLD = 2.0;

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

        // Baseline window: the BASELINE_LOOKBACK_DAYS immediately before the
        // current lookback window starts — i.e. this endpoint's own recent
        // history, kept separate from the window being diagnosed so "current"
        // and "normal" are never the same data compared against itself.
        LocalDateTime baselineStart = cutoff.minusDays(BASELINE_LOOKBACK_DAYS);
        List<Telemetry> baselineTelemetry = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampBetween(baselineStart, cutoff)
                : telemetryRepository.findByApplicationNameAndTimestampBetween(applicationName, baselineStart, cutoff);
        Map<String, List<Telemetry>> baselineByEndpoint = baselineTelemetry.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        List<com.veloxdiag.server.diagnosis.engine.RuleDefinitionEntity> rules =
                ruleEngineService.loadEnabledRules();

        Map<String, List<SlowQueryPlan>> plansByEndpoint = slowQueryPlanRepository
                .findByContainsSeqScanTrueAndTimestampAfter(cutoff).stream()
                .collect(Collectors.groupingBy(p -> EndpointNormalizer.normalize(p.getEndpoint())));

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<SlowQueryPlan> plansForEndpoint = plansByEndpoint.getOrDefault(endpoint, List.of());
            List<Telemetry> baselineForEndpoint = baselineByEndpoint.getOrDefault(endpoint, List.of());
            findings.addAll(computeEndpointFindings(endpoint, entry.getValue(), plansForEndpoint, baselineForEndpoint, rules, thresholds));
        }

        return findings;
    }

    // Batched path — used by runDiagnosis, takes pre-fetched rules, pre-fetched
    // (already endpoint-filtered) seq-scan plans, pre-fetched baseline-window
    // telemetry for this endpoint, and the resolved thresholds for the
    // application being diagnosed.
    private List<DiagnosisFinding> computeEndpointFindings(
            String endpoint, List<Telemetry> records, List<SlowQueryPlan> seqScanPlans, List<Telemetry> baselineRecords,
            List<com.veloxdiag.server.diagnosis.engine.RuleDefinitionEntity> rules, Thresholds thresholds) {
        List<DiagnosisFinding> endpointFindings = new ArrayList<>();
        endpointFindings.addAll(checkSlowRequest(endpoint, records, thresholds));
        endpointFindings.addAll(checkHighErrorRate(endpoint, records, thresholds));
        endpointFindings.addAll(checkServerErrors(endpoint, records, thresholds));
        endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records, thresholds));
        endpointFindings.addAll(checkMissingIndexCandidateFromPlans(endpoint, seqScanPlans, thresholds));
        endpointFindings.addAll(checkPerformanceRegression(endpoint, records, baselineRecords));

        List<DiagnosisFinding> combined = new ArrayList<>(endpointFindings);
        combined.addAll(correlateFindings(endpoint, records, endpointFindings, thresholds));
        combined.addAll(ruleEngineService.evaluate(endpoint, records, rules));

        return combined;
    }

    // Unbatched path — used only by getFindingsForEndpoint, which evaluates a
    // single endpoint on demand (e.g. the "Explain this" narrative fetch). This
    // path has no application context (endpoint names are looked up without
    // knowing which app they belong to), so it uses the DEFAULT_KEY thresholds
    // and an all-apps baseline window — same narrow gap noted below, now also
    // applying to the baseline: if the same endpoint name exists in two apps,
    // their history gets pooled here instead of kept separate.
    private List<DiagnosisFinding> computeEndpointFindings(String endpoint, List<Telemetry> records,
                                                             LocalDateTime cutoff) {
        Thresholds thresholds = resolveThresholds(null);
        LocalDateTime baselineStart = cutoff.minusDays(BASELINE_LOOKBACK_DAYS);
        List<Telemetry> baselineRecords = telemetryRepository.findByTimestampBetween(baselineStart, cutoff).stream()
                .filter(t -> endpoint.equals(EndpointNormalizer.normalize(t.getEndpoint())))
                .collect(Collectors.toList());

        List<DiagnosisFinding> endpointFindings = new ArrayList<>();
        endpointFindings.addAll(checkSlowRequest(endpoint, records, thresholds));
        endpointFindings.addAll(checkHighErrorRate(endpoint, records, thresholds));
        endpointFindings.addAll(checkServerErrors(endpoint, records, thresholds));
        endpointFindings.addAll(checkPossibleNPlusOne(endpoint, records, thresholds));
        endpointFindings.addAll(checkMissingIndexCandidate(endpoint, cutoff, thresholds));
        endpointFindings.addAll(checkPerformanceRegression(endpoint, records, baselineRecords));

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

    // Population standard deviation of a set of durations, given their mean.
    // Used to turn "is this endpoint slow" from a flat yes/no against one global
    // number into "how *consistently* slow is it" — a tight cluster around a
    // high mean is a real systemic pattern; a high mean caused by one or two
    // outliers among mostly-fast requests is noise, and should be flagged with
    // lower confidence even if it crosses the same threshold.
    private static double stdDev(List<Long> values, double mean) {
        if (values.size() < 2) return 0.0;
        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();
        return Math.sqrt(sumSquaredDiff / values.size());
    }

    private List<DiagnosisFinding> checkSlowRequest(String endpoint, List<Telemetry> records, Thresholds thresholds) {
        List<Long> durations = records.stream().map(Telemetry::getDurationMs).collect(Collectors.toList());
        double avgDuration = durations.stream().mapToLong(Long::longValue).average().orElse(0.0);

        if (avgDuration > thresholds.slowRequestThresholdMs) {
            boolean insufficientSamples = records.size() < MIN_SAMPLE_COUNT;
            String severity = insufficientSamples
                    ? "LOW"
                    : (avgDuration > 5000 ? "HIGH" : (avgDuration > 2000 ? "MEDIUM" : "LOW"));

            double stdDeviation = stdDev(durations, avgDuration);
            // Coefficient of variation — stddev relative to the mean. Low CV means
            // requests are consistently slow together (a real pattern); high CV
            // means the average is being dragged up by a few spikes among mostly
            // normal requests (weaker evidence of a systemic issue).
            double coefficientOfVariation = avgDuration > 0 ? stdDeviation / avgDuration : 0.0;

            String confidence;
            if (insufficientSamples) {
                confidence = "LOW";
            } else if (coefficientOfVariation < 0.3) {
                confidence = "HIGH";
            } else if (coefficientOfVariation < 0.6) {
                confidence = "MEDIUM";
            } else {
                confidence = "LOW";
            }

            String conditionMatched = String.format(
                    "avgDurationMs(%.0f) > slowRequestThresholdMs(%.0f), sampleCount(%d) >= minSampleCount(%d): %s",
                    avgDuration, thresholds.slowRequestThresholdMs, records.size(), MIN_SAMPLE_COUNT, !insufficientSamples);

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageDurationMs", avgDuration);
            evidence.put("stdDeviationMs", stdDeviation);
            evidence.put("coefficientOfVariation", coefficientOfVariation);
            evidence.put("sampleCount", records.size());
            evidence.put("insufficientSampleSize", insufficientSamples);
            evidence.put("conditionMatched", conditionMatched);

            String message = insufficientSamples
                    ? String.format("Endpoint %s is averaging %.0fms per request, above the %.0fms threshold — " +
                                    "but this is based on only %d sample(s), too few to reliably call this a systemic " +
                                    "slowdown rather than a one-off (e.g. cold start).",
                            endpoint, avgDuration, thresholds.slowRequestThresholdMs, records.size())
                    : String.format("Endpoint %s is averaging %.0fms per request (\u00b1%.0fms, CV %.2f), above the %.0fms " +
                                    "threshold. %s",
                            endpoint, avgDuration, stdDeviation, coefficientOfVariation, thresholds.slowRequestThresholdMs,
                            coefficientOfVariation < 0.3
                                    ? "Requests are consistently slow together — this looks like a systemic pattern, not an outlier."
                                    : "Duration varies a lot between requests — the average may be driven by a few spikes rather than a consistent slowdown.");

            return List.of(new DiagnosisFinding("SLOW_REQUEST", severity, endpoint, message, evidence, confidence, List.of()));
        }
        return List.of();
    }

    // Statistical anomaly detection against the endpoint's OWN history — the
    // piece that separates this from a flat-threshold system. Instead of
    // asking "is this endpoint above one global number," this asks "is this
    // endpoint currently behaving differently than IT normally does."
    //
    // Deliberately conservative: requires MIN_BASELINE_SAMPLE_COUNT samples of
    // real history before computing anything. If an endpoint is new or hasn't
    // accumulated enough telemetry yet, this returns no finding rather than
    // fabricating a baseline from a handful of points — a thin, noisy baseline
    // is worse than no baseline, since it would produce false confidence.
    //
    // Only flags REGRESSIONS (current worse than baseline), not improvements —
    // an endpoint getting faster than its baseline is not a diagnosis finding.
    private List<DiagnosisFinding> checkPerformanceRegression(String endpoint, List<Telemetry> currentRecords,
                                                                 List<Telemetry> baselineRecords) {
        List<Long> baselineDurations = baselineRecords.stream().map(Telemetry::getDurationMs).collect(Collectors.toList());

        if (baselineDurations.size() < MIN_BASELINE_SAMPLE_COUNT || currentRecords.isEmpty()) {
            return List.of();
        }

        double baselineMean = baselineDurations.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double baselineStdDeviation = stdDev(baselineDurations, baselineMean);

        double currentMean = currentRecords.stream()
                .mapToLong(Telemetry::getDurationMs)
                .average()
                .orElse(0.0);

        // Guard against a near-flat baseline (stddev ~0) producing an absurd
        // z-score off a tiny denominator — fall back to a simple ratio check
        // in that case instead of dividing by (near) zero.
        boolean flatBaseline = baselineStdDeviation < 1.0;
        double zScore = flatBaseline ? 0.0 : (currentMean - baselineMean) / baselineStdDeviation;
        double ratio = baselineMean > 0 ? currentMean / baselineMean : 0.0;

        boolean isRegression = flatBaseline
                ? ratio >= 1.5 && (currentMean - baselineMean) > 50 // at least 50ms of real, not rounding-noise, drift
                : zScore >= REGRESSION_Z_SCORE_THRESHOLD;

        if (!isRegression) {
            return List.of();
        }

        String severity;
        if (flatBaseline) {
            severity = ratio >= 3.0 ? "HIGH" : (ratio >= 2.0 ? "MEDIUM" : "LOW");
        } else {
            severity = zScore >= 4.0 ? "HIGH" : (zScore >= 3.0 ? "MEDIUM" : "LOW");
        }

        String confidence = baselineDurations.size() >= 50 ? "HIGH" : "MEDIUM";

        String conditionMatched = flatBaseline
                ? String.format("currentMeanMs(%.0f) / baselineMeanMs(%.0f) = %.2fx >= 1.5x, baselineSampleCount(%d)",
                        currentMean, baselineMean, ratio, baselineDurations.size())
                : String.format("zScore(%.2f) = (currentMeanMs(%.0f) - baselineMeanMs(%.0f)) / baselineStdDevMs(%.0f) >= %.1f, baselineSampleCount(%d)",
                        zScore, currentMean, baselineMean, baselineStdDeviation, REGRESSION_Z_SCORE_THRESHOLD, baselineDurations.size());

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("currentMeanMs", currentMean);
        evidence.put("currentSampleCount", currentRecords.size());
        evidence.put("baselineMeanMs", baselineMean);
        evidence.put("baselineStdDeviationMs", baselineStdDeviation);
        evidence.put("baselineSampleCount", baselineDurations.size());
        evidence.put("baselineWindowDays", BASELINE_LOOKBACK_DAYS);
        evidence.put("zScore", zScore);
        evidence.put("conditionMatched", conditionMatched);

        String message = String.format(
                "Endpoint %s is currently averaging %.0fms, which is a real regression against its own %d-day baseline " +
                        "of %.0fms (\u00b1%.0fms, n=%d) — %s. This is a change relative to how this specific endpoint normally " +
                        "behaves, not just a comparison against a fixed threshold.",
                endpoint, currentMean, BASELINE_LOOKBACK_DAYS, baselineMean, baselineStdDeviation, baselineDurations.size(),
                flatBaseline
                        ? String.format("%.1fx slower than baseline", ratio)
                        : String.format("%.1f standard deviations above baseline", zScore));

        return List.of(new DiagnosisFinding("PERFORMANCE_REGRESSION", severity, endpoint, message, evidence, confidence, List.of()));
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

            double stdDeviation = stdDev(counts, avgQueryCount);
            // How many of the observed requests actually crossed the threshold, not
            // just the single max — a pattern that recurs across most requests is
            // stronger evidence than one spike among otherwise-normal counts.
            long overThresholdCount = counts.stream()
                    .filter(c -> c > thresholds.possibleNPlusOneQueryThreshold)
                    .count();
            double recurrenceRate = counts.isEmpty() ? 0.0 : (double) overThresholdCount / counts.size();

            String confidence;
            if (insufficientSamples) {
                confidence = "LOW";
            } else if (recurrenceRate >= 0.7) {
                confidence = "HIGH";
            } else if (recurrenceRate >= 0.3) {
                confidence = "MEDIUM";
            } else {
                confidence = "LOW";
            }

            String conditionMatched = String.format(
                    "maxQueryCount(%d) > nPlusOneThreshold(%d), recurring in %d/%d requests (%.0f%%)",
                    maxQueryCount, thresholds.possibleNPlusOneQueryThreshold, overThresholdCount, counts.size(), recurrenceRate * 100);

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageQueryCount", avgQueryCount);
            evidence.put("maxQueryCount", maxQueryCount);
            evidence.put("stdDeviationQueryCount", stdDeviation);
            evidence.put("recurrenceRate", recurrenceRate);
            evidence.put("sampleCount", counts.size());
            evidence.put("insufficientSampleSize", insufficientSamples);
            evidence.put("conditionMatched", conditionMatched);

            String message = insufficientSamples
                    ? String.format("Endpoint %s spiked to %d SQL queries in at least one request (average %.1f), " +
                                    "suggesting an N+1 query pattern — but only %d sample(s) with a query count were " +
                                    "observed, too few to confirm this is a recurring pattern rather than a single event.",
                            endpoint, maxQueryCount, avgQueryCount, counts.size())
                    : String.format("Endpoint %s spiked to %d SQL queries in at least one request (average %.1f across %d samples), " +
                                    "recurring in %.0f%% of observed requests — suggesting an N+1 query pattern rather than a single " +
                                    "efficient fetch.",
                            endpoint, maxQueryCount, avgQueryCount, counts.size(), recurrenceRate * 100);

            return List.of(new DiagnosisFinding("POSSIBLE_N_PLUS_ONE", severity, endpoint, message, evidence, confidence, List.of()));
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