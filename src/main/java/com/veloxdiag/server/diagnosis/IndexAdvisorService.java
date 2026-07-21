package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IndexAdvisorService {

    // Only consider endpoints slow enough to matter — now live-configurable via Settings
    private double minAvgDurationMs = 1000.0;

    // Need enough samples to trust the variance calculation
    private static final int MIN_SAMPLE_COUNT = 6;

    // Coefficient of variation below this = "consistently slow" — now live-configurable via Settings
    // (stdDev / avg — e.g. 0.15 means samples typically vary by only 15% from the average)
    private double lowVarianceThreshold = 0.20;

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;

    public IndexAdvisorService(TelemetryRepository telemetryRepository, TelemetryWindowSettings windowSettings) {
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
    }

    public double getMinAvgDurationMs() { return minAvgDurationMs; }
    public void setMinAvgDurationMs(double minAvgDurationMs) { this.minAvgDurationMs = minAvgDurationMs; }

    public double getLowVarianceThreshold() { return lowVarianceThreshold; }
    public void setLowVarianceThreshold(double lowVarianceThreshold) { this.lowVarianceThreshold = lowVarianceThreshold; }

    public List<IndexAdvisorFinding> analyzeCandidates() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> all = telemetryRepository.findByTimestampAfter(cutoff);

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