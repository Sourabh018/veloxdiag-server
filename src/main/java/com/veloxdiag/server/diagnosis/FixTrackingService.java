package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.diagnosis.engine.EndpointMetrics;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implements the "before/after" and "Regression Watch" pieces the original
 * spec described but the codebase never had (see FixSnapshot javadoc for
 * the distinction from the existing PERFORMANCE_REGRESSION baseline check).
 *
 * markAsFixed() freezes current metrics as the "before" side. Every later
 * read (getComparisons) recomputes the "after" side live from telemetry
 * timestamped after markedFixedAt — no polling job, no stale cache, always
 * reflects whatever has arrived since the fix was marked.
 */
@Service
public class FixTrackingService {

    // Minimum fresh samples after the fix before declaring IMPROVED/REGRESSED/
    // NO_CHANGE — below this, status stays WATCHING rather than drawing a
    // conclusion off 1-2 lucky/unlucky requests.
    private static final long MIN_AFTER_SAMPLES = 5;

    // >=10% faster counts as a real improvement; >=5% slower counts as a
    // regression. The gap between those two thresholds is deliberate — noise
    // in request timing shouldn't flip a finding between IMPROVED and
    // REGRESSED on small fluctuations.
    private static final double IMPROVEMENT_THRESHOLD = 0.10;
    private static final double REGRESSION_THRESHOLD = 0.05;

    private final FixSnapshotRepository fixSnapshotRepository;
    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;

    public FixTrackingService(FixSnapshotRepository fixSnapshotRepository,
                               TelemetryRepository telemetryRepository,
                               TelemetryWindowSettings windowSettings) {
        this.fixSnapshotRepository = fixSnapshotRepository;
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
    }

    /**
     * Captures the "before" snapshot at the moment a fix is marked, using the
     * same lookback window Diagnosis normally uses for this app, filtered to
     * this one normalized endpoint.
     */
    public FixSnapshot markAsFixed(String applicationName, String endpoint, String ruleType, String note) {
        LocalDateTime now = LocalDateTime.now();
        int lookbackDays = windowSettings.getLookbackDays(applicationName);
        LocalDateTime windowStart = now.minusDays(lookbackDays);

        List<Telemetry> beforeRecords = fetchRecordsForEndpoint(applicationName, endpoint, windowStart, now);
        Map<String, Double> metrics = EndpointMetrics.compute(beforeRecords);

        FixSnapshot snapshot = new FixSnapshot(
                applicationName,
                endpoint,
                ruleType,
                note,
                now,
                metrics.get("avgDurationMs"),
                metrics.get("maxQueryCount"),
                metrics.containsKey("sampleCount") ? metrics.get("sampleCount").longValue() : 0L
        );
        return fixSnapshotRepository.save(snapshot);
    }

    public List<FixComparisonDto> getComparisons(String applicationName) {
        List<FixSnapshot> snapshots = (applicationName == null || applicationName.isBlank())
                ? fixSnapshotRepository.findAllByOrderByMarkedFixedAtDesc()
                : fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(applicationName);

        return snapshots.stream().map(this::toComparison).collect(Collectors.toList());
    }

    private FixComparisonDto toComparison(FixSnapshot snapshot) {
        List<Telemetry> afterRecords = fetchRecordsForEndpoint(
                snapshot.getApplicationName(), snapshot.getEndpoint(),
                snapshot.getMarkedFixedAt(), LocalDateTime.now());

        Map<String, Double> afterMetrics = EndpointMetrics.compute(afterRecords);
        long afterSampleCount = afterMetrics.containsKey("sampleCount") ? afterMetrics.get("sampleCount").longValue() : 0L;

        FixComparisonDto dto = new FixComparisonDto();
        dto.setId(snapshot.getId());
        dto.setApplicationName(snapshot.getApplicationName());
        dto.setEndpoint(snapshot.getEndpoint());
        dto.setRuleType(snapshot.getRuleType());
        dto.setNote(snapshot.getNote());
        dto.setMarkedFixedAt(snapshot.getMarkedFixedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        dto.setBeforeAvgDurationMs(snapshot.getBeforeAvgDurationMs());
        dto.setBeforeMaxQueryCount(snapshot.getBeforeMaxQueryCount());
        dto.setBeforeSampleCount(snapshot.getBeforeSampleCount());
        dto.setAfterSampleCount(afterSampleCount);

        if (afterSampleCount < MIN_AFTER_SAMPLES) {
            dto.setStatus(FixSnapshot.Status.WATCHING.name());
            // still show whatever partial after-data exists, just without a verdict
            dto.setAfterAvgDurationMs(afterMetrics.get("avgDurationMs"));
            dto.setAfterMaxQueryCount(afterMetrics.get("maxQueryCount"));
            return dto;
        }

        double afterAvg = afterMetrics.getOrDefault("avgDurationMs", 0.0);
        dto.setAfterAvgDurationMs(afterAvg);
        dto.setAfterMaxQueryCount(afterMetrics.get("maxQueryCount"));

        Double beforeAvg = snapshot.getBeforeAvgDurationMs();
        if (beforeAvg == null || beforeAvg == 0.0) {
            dto.setStatus(FixSnapshot.Status.WATCHING.name());
            return dto;
        }

        double changeRatio = (beforeAvg - afterAvg) / beforeAvg; // positive = faster
        double improvementPercent = changeRatio * 100.0;
        dto.setImprovementPercent(improvementPercent);

        FixSnapshot.Status status;
        if (changeRatio >= IMPROVEMENT_THRESHOLD) {
            status = FixSnapshot.Status.IMPROVED;
        } else if (changeRatio <= -REGRESSION_THRESHOLD) {
            status = FixSnapshot.Status.REGRESSED;
        } else {
            status = FixSnapshot.Status.NO_CHANGE;
        }
        dto.setStatus(status.name());

        return dto;
    }

    // Same pattern DiagnosisService uses: pull the app's raw telemetry in a
    // timestamp range, then filter down to records whose NORMALIZED endpoint
    // matches — normalization happens per-record since raw endpoints carry
    // real IDs (e.g. /api/exams/9a352dba.../submit) that all collapse to the
    // same tracked endpoint (/api/exams/{id}/submit).
    private List<Telemetry> fetchRecordsForEndpoint(String applicationName, String endpoint,
                                                      LocalDateTime start, LocalDateTime end) {
        List<Telemetry> records = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampBetween(start, end)
                : telemetryRepository.findByApplicationNameAndTimestampBetween(applicationName, start, end);

        return records.stream()
                .filter(t -> endpoint.equals(EndpointNormalizer.normalize(t.getEndpoint())))
                .collect(Collectors.toList());
    }
}