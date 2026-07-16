package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiagnosisService {

    private static final double SLOW_REQUEST_THRESHOLD_MS = 1000.0;
    private static final long HIGH_ERROR_RATE_THRESHOLD = 3;
    private static final int SERVER_ERROR_STATUS_THRESHOLD = 500;

    private final TelemetryRepository telemetryRepository;

    public DiagnosisService(TelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    public List<DiagnosisFinding> runDiagnosis() {
        List<Telemetry> allTelemetry = telemetryRepository.findAll();
        List<DiagnosisFinding> findings = new ArrayList<>();

        Map<String, List<Telemetry>> byEndpoint = allTelemetry.stream()
                .collect(Collectors.groupingBy(Telemetry::getEndpoint));

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

        if (avgDuration > SLOW_REQUEST_THRESHOLD_MS) {
            String severity = avgDuration > 5000 ? "HIGH" : (avgDuration > 2000 ? "MEDIUM" : "LOW");
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("averageDurationMs", avgDuration);
            evidence.put("sampleCount", records.size());

            return List.of(new DiagnosisFinding(
                    "SLOW_REQUEST",
                    severity,
                    endpoint,
                    String.format("Endpoint %s is averaging %.0fms per request, above the %.0fms threshold.",
                            endpoint, avgDuration, SLOW_REQUEST_THRESHOLD_MS),
                    evidence
            ));
        }
        return List.of();
    }

    private List<DiagnosisFinding> checkHighErrorRate(String endpoint, List<Telemetry> records) {
        long errorCount = records.stream()
                .filter(t -> t.getStatus() >= 400)
                .count();

        if (errorCount >= HIGH_ERROR_RATE_THRESHOLD) {
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
                .filter(t -> t.getStatus() >= SERVER_ERROR_STATUS_THRESHOLD)
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