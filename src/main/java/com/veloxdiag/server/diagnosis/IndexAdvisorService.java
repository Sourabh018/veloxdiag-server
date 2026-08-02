package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class IndexAdvisorService {

    static final String DEFAULT_KEY = "__default__";

    // Only consider endpoints slow enough to matter — now live-configurable
    // per application via Settings.
    private static final double DEFAULT_MIN_AVG_DURATION_MS = 1000.0;

    // Need enough samples to trust the variance calculation
    private static final int MIN_SAMPLE_COUNT = 6;

    // Coefficient of variation below this = "consistently slow" — now
    // live-configurable per application via Settings.
    // (stdDev / avg — e.g. 0.15 means samples typically vary by only 15% from the average)
    private static final double DEFAULT_LOW_VARIANCE_THRESHOLD = 0.20;

    private final Map<String, Double> minAvgDurationMsByApp = new ConcurrentHashMap<>();
    private final Map<String, Double> lowVarianceThresholdByApp = new ConcurrentHashMap<>();

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;

    public IndexAdvisorService(TelemetryRepository telemetryRepository, TelemetryWindowSettings windowSettings) {
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
        minAvgDurationMsByApp.put(DEFAULT_KEY, DEFAULT_MIN_AVG_DURATION_MS);
        lowVarianceThresholdByApp.put(DEFAULT_KEY, DEFAULT_LOW_VARIANCE_THRESHOLD);
    }

    private static String resolveKey(String applicationName) {
        return (applicationName == null || applicationName.isBlank()) ? DEFAULT_KEY : applicationName;
    }

    public double getMinAvgDurationMs(String applicationName) {
        return minAvgDurationMsByApp.getOrDefault(resolveKey(applicationName), DEFAULT_MIN_AVG_DURATION_MS);
    }

    public void setMinAvgDurationMs(String applicationName, double minAvgDurationMs) {
        minAvgDurationMsByApp.put(resolveKey(applicationName), minAvgDurationMs);
    }

    public double getLowVarianceThreshold(String applicationName) {
        return lowVarianceThresholdByApp.getOrDefault(resolveKey(applicationName), DEFAULT_LOW_VARIANCE_THRESHOLD);
    }

    public void setLowVarianceThreshold(String applicationName, double lowVarianceThreshold) {
        lowVarianceThresholdByApp.put(resolveKey(applicationName), lowVarianceThreshold);
    }

    // No-arg overloads operate on the DEFAULT_KEY bucket — kept for any
    // caller that hasn't been updated to pass an applicationName yet.
    public double getMinAvgDurationMs() { return getMinAvgDurationMs(null); }
    public void setMinAvgDurationMs(double minAvgDurationMs) { setMinAvgDurationMs(null, minAvgDurationMs); }
    public double getLowVarianceThreshold() { return getLowVarianceThreshold(null); }
    public void setLowVarianceThreshold(double lowVarianceThreshold) { setLowVarianceThreshold(null, lowVarianceThreshold); }

    public List<IndexAdvisorFinding> analyzeCandidates() {
        return analyzeCandidates(null);
    }

    // App-selector-scoped version. Blank/null applicationName means "All Apps" —
    // same combined behavior as analyzeCandidates() above. Thresholds are now
    // resolved for the specific application being analyzed, not one shared
    // global value.
    public List<IndexAdvisorFinding> analyzeCandidates(String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays(applicationName));
        List<Telemetry> all = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampAfter(cutoff)
                : telemetryRepository.findByApplicationNameAndTimestampAfter(applicationName, cutoff);

        double minAvgDurationMs = getMinAvgDurationMs(applicationName);
        double lowVarianceThreshold = getLowVarianceThreshold(applicationName);

        Map<String, List<Telemetry>> byEndpoint = all.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        List<IndexAdvisorFinding> candidates = new ArrayList<>();

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<Telemetry> records = entry.getValue();

            if (records.size() < MIN_SAMPLE_COUNT) {
                continue; // not enough data to trust variance
            }

            double avg = records.stream().mapToLong(Telemetry::getDurationMs).average().orElse(0.0);

            if (avg < minAvgDurationMs) {
                continue; // not slow enough to be worth flagging
            }

            double variance = records.stream()
                    .mapToDouble(t -> Math.pow(t.getDurationMs() - avg, 2))
                    .average()
                    .orElse(0.0);
            double stdDev = Math.sqrt(variance);
            double coefficientOfVariation = avg == 0 ? 0 : stdDev / avg;

            if (coefficientOfVariation <= lowVarianceThreshold) {
                String message = String.format(
                        "Endpoint %s is consistently slow (avg %.0fms, low variance across %d requests). " +
                        "This pattern — slow on every call rather than only under load — is often consistent with a missing database index. " +
                        "Not confirmed: this is a heuristic based on response time consistency, not actual query/execution-plan inspection.",
                        endpoint, avg, records.size()
                );

                candidates.add(new IndexAdvisorFinding(
                        endpoint, avg, stdDev, coefficientOfVariation, records.size(), message
                ));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.getAvgDurationMs(), a.getAvgDurationMs()));

        return candidates;
    }
}