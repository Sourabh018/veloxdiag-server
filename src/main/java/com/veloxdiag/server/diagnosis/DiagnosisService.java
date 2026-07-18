package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiagnosisService {

    // Now mutable — adjustable at runtime via Settings, defaults match original hardcoded values
    private double slowRequestThresholdMs = 1000.0;
    private long highErrorRateThreshold = 3;
    private int serverErrorStatusThreshold = 500;
    private long possibleNPlusOneQueryThreshold = 15;

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;

    public DiagnosisService(TelemetryRepository telemetryRepository, TelemetryWindowSettings windowSettings) {
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
    }

    public double getSlowRequestThresholdMs() { return slowRequestThresholdMs; }
    public void setSlowRequestThresholdMs(double value) { this.slowRequestThresholdMs = value; }

    public long getHighErrorRateThreshold() { return highErrorRateThreshold; }
    public void setHighErrorRateThreshold(long value) { this.highErrorRateThreshold = value; }

    public int getServerErrorStatusThreshold() { return serverErrorStatusThreshold; }
    public void setServerErrorStatusThreshold(int value) { this.serverErrorStatusThreshold = value; }

    public long getPossibleNPlusOneQueryThreshold() { return possibleNPlusOneQueryThreshold; }
    public void setPossibleNPlusOneQueryThreshold(long value) { this.possibleNPlusOneQueryThreshold = value; }

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

            findings.addAll(endpointFindings);
            // Correlation runs after the individual checks for this endpoint, since it
            // needs to know which finding types already fired here before it decides
            // whether a root-cause link between them is worth surfacing.
            findings.addAll(correlateFindings(endpoint, records, endpointFindings));
        }

        return findings;
    }

    private List<DiagnosisFinding> checkSlowRequest(String endpoint, List<Telemetry> records) {
        double avgDuration = records.stream()
                .mapToLong(Telemetry::getDurationMs)
                .average()
                .orElse(0.0);

        if (avgDuration > slowRequestThresholdMs) {
            String severity = avgDuration > 5000 ? "HIGH" : (avgDuration > 2000 ? "MEDIUM" : "LOW");
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageDurationMs", avgDuration);
            evidence.put("sampleCount", records.size());

            return List.of(new DiagnosisFinding(
                    "SLOW_REQUEST",
                    severity,
                    endpoint,
                    String.format("Endpoint %s is averaging %.0fms per request, above the %.0fms threshold.",
                            endpoint, avgDuration, slowRequestThresholdMs),
                    evidence
            ));
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
            String severity = maxQueryCount > 50 ? "HIGH" : (maxQueryCount > 25 ? "MEDIUM" : "LOW");
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageQueryCount", avgQueryCount);
            evidence.put("maxQueryCount", maxQueryCount);
            evidence.put("sampleCount", counts.size());

            return List.of(new DiagnosisFinding(
                    "POSSIBLE_N_PLUS_ONE",
                    severity,
                    endpoint,
                    String.format("Endpoint %s spiked to %d SQL queries in at least one request (average %.1f across %d samples), " +
                                    "suggesting an N+1 query pattern rather than a single efficient fetch.",
                            endpoint, maxQueryCount, avgQueryCount, counts.size()),
                    evidence
            ));
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

        if (spikyAvgDurationOpt.isPresent() && normalAvgDurationOpt.isPresent() && normalRecords.size() >= 3) {
            double spikyAvg = spikyAvgDurationOpt.getAsDouble();
            double normalAvg = normalAvgDurationOpt.getAsDouble();
            double ratio = normalAvg > 0 ? spikyAvg / normalAvg : 0;

            evidence.put("spikyAvgDurationMs", spikyAvg);
            evidence.put("normalAvgDurationMs", normalAvg);
            evidence.put("durationRatio", ratio);

            // HIGH: spiky requests are at least 2x slower than normal ones on the
            // same endpoint — a real, measured difference, not just co-occurrence.
            confidence = ratio >= 2.0 ? "HIGH" : "MEDIUM";
            message = String.format(
                    "High duration on %s is likely driven by its N+1 query pattern: requests with more than %d queries " +
                            "averaged %.0fms (n=%d) vs %.0fms (n=%d) for requests at or below that threshold — roughly %.1fx slower.",
                    endpoint, possibleNPlusOneQueryThreshold, spikyAvg, spikyRecords.size(), normalAvg, normalRecords.size(), ratio);
        } else {
            // Both findings fired, but we don't have enough of a baseline (e.g. every
            // sample was a spike, or too few normal-range samples) to measure a ratio.
            // Still worth surfacing the correlation, just with lower confidence and an
            // honest note about why.
            confidence = "MEDIUM";
            message = String.format(
                    "SLOW_REQUEST and POSSIBLE_N_PLUS_ONE both fired for %s, suggesting a link between query volume and " +
                            "duration, but there weren't enough normal-range samples on this endpoint to measure the exact ratio.",
                    endpoint);
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
}