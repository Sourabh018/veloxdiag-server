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

    public List<DiagnosisFinding> runDiagnosis() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> allTelemetry = telemetryRepository.findByTimestampAfter(cutoff);
        List<DiagnosisFinding> findings = new ArrayList<>();

        Map<String, List<Telemetry>> byEndpoint = allTelemetry.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<Telemetry> records = entry.getValue();

            findings.addAll(checkSlowRequest(endpoint, records));
            findings.addAll(checkHighErrorRate(endpoint, records));
            findings.addAll(checkServerErrors(endpoint, records));
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
}