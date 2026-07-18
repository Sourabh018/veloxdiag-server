package com.veloxdiag.server.diagnosis.engine;

import com.veloxdiag.server.entity.Telemetry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Computes a fixed set of named metrics for one endpoint's telemetry records,
 * once per endpoint per diagnosis run. This map is the "vocabulary" that
 * RuleCondition.metric strings reference — a rule author (via the API) picks
 * a key from this list, not a raw Java field.
 *
 * Adding a new metric here immediately makes it available to every existing
 * and future data-driven rule, with zero changes needed to RuleEngineService
 * or any stored rule — that's the actual extensibility point of this design.
 */
public class EndpointMetrics {

    public static Map<String, Double> compute(List<Telemetry> records) {
        Map<String, Double> metrics = new HashMap<>();

        if (records.isEmpty()) {
            return metrics;
        }

        double sampleCount = records.size();
        metrics.put("sampleCount", sampleCount);

        double avgDurationMs = records.stream()
                .mapToLong(Telemetry::getDurationMs)
                .average()
                .orElse(0.0);
        metrics.put("avgDurationMs", avgDurationMs);

        double maxDurationMs = records.stream()
                .mapToLong(Telemetry::getDurationMs)
                .max()
                .orElse(0);
        metrics.put("maxDurationMs", maxDurationMs);

        long errorCount = records.stream()
                .filter(t -> t.getStatus() >= 400)
                .count();
        metrics.put("errorCount", (double) errorCount);
        metrics.put("errorRate", errorCount / sampleCount);

        long serverErrorCount = records.stream()
                .filter(t -> t.getStatus() >= 500)
                .count();
        metrics.put("serverErrorCount", (double) serverErrorCount);

        List<Long> queryCounts = records.stream()
                .map(Telemetry::getQueryCount)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (!queryCounts.isEmpty()) {
            double avgQueryCount = queryCounts.stream().mapToLong(Long::longValue).average().orElse(0.0);
            long maxQueryCount = queryCounts.stream().mapToLong(Long::longValue).max().orElse(0);
            metrics.put("avgQueryCount", avgQueryCount);
            metrics.put("maxQueryCount", (double) maxQueryCount);
        }
        // If no record has a queryCount (older Starter versions / non-JPA apps),
        // avgQueryCount/maxQueryCount are simply absent from the map — any rule
        // referencing them will just never fire for this endpoint, via
        // RuleCondition.evaluate()'s null-safe handling. No fabricated zero.

        return metrics;
    }
}