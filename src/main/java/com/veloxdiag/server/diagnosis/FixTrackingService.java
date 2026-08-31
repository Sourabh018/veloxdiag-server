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
    // NO_CHANGE on a LOW-noise endpoint. Noisier endpoints require more —
    // see minSamplesFor() below. Below the applicable minimum, status stays
    // WATCHING rather than drawing a conclusion off a handful of requests.
    private static final long MIN_AFTER_SAMPLES_LOW_NOISE = 5;
    private static final long MIN_AFTER_SAMPLES_MEDIUM_NOISE = 10;
    private static final long MIN_AFTER_SAMPLES_HIGH_NOISE = 20;

    // >=10% faster counts as a real improvement; >=5% slower counts as a
    // regression. The gap between those two thresholds is deliberate — noise
    // in request timing shouldn't flip a finding between IMPROVED and
    // REGRESSED on small fluctuations. This is a FLOOR — on noisy endpoints
    // the noise-band check below (see NOISE_BAND_MULTIPLIER) usually demands
    // more than this before granting a verdict anyway.
    private static final double IMPROVEMENT_THRESHOLD = 0.10;
    private static final double REGRESSION_THRESHOLD = 0.05;

    // Caught via a real false positive during testing: /api/auth/register
    // showed a 45% "improvement" (1339ms -> 732ms) that was actually smaller
    // than its own pre-fix stdDev (1690ms) — i.e. well within that endpoint's
    // normal random swing, not a real change. Fix: the after-average must
    // move outside a noise band of [beforeAvg - k*beforeStdDev, beforeAvg +
    // k*beforeStdDev] before a verdict is granted at all; inside the band,
    // status stays WATCHING/NO_CHANGE regardless of what the raw percentage
    // says. k=0.5 is deliberately conservative (a full 1.0*stdDev band would
    // let too many real improvements slip through as "just noise" on already-
    // noisy endpoints, which is exactly the population this feature most
    // needs to work on).
    private static final double NOISE_BAND_MULTIPLIER = 0.5;

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
     * this one normalized endpoint. Also captures the "before" standard
     * deviation — the endpoint's natural noise level — used later to gate
     * verdicts (see NOISE_BAND_MULTIPLIER).
     */
    public FixSnapshot markAsFixed(String applicationName, String endpoint, String ruleType, String note) {
        LocalDateTime now = LocalDateTime.now();
        int lookbackDays = windowSettings.getLookbackDays(applicationName);
        LocalDateTime windowStart = now.minusDays(lookbackDays);

        List<Telemetry> beforeRecords = fetchRecordsForEndpoint(applicationName, endpoint, windowStart, now);
        Map<String, Double> metrics = EndpointMetrics.compute(beforeRecords);
        Double beforeStdDev = computeDurationStdDev(beforeRecords);

        FixSnapshot snapshot = new FixSnapshot(
                applicationName,
                endpoint,
                ruleType,
                note,
                now,
                metrics.get("avgDurationMs"),
                metrics.get("maxQueryCount"),
                metrics.containsKey("sampleCount") ? metrics.get("sampleCount").longValue() : 0L,
                beforeStdDev
        );
        return fixSnapshotRepository.save(snapshot);
    }

    public List<FixComparisonDto> getComparisons(String applicationName) {
        List<FixSnapshot> snapshots = (applicationName == null || applicationName.isBlank())
                ? fixSnapshotRepository.findAllByOrderByMarkedFixedAtDesc()
                : fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(applicationName);

        return snapshots.stream().map(this::toComparison).collect(Collectors.toList());
    }

    // Noisier "before" endpoints need more after-samples before a verdict —
    // a handful of requests can't distinguish signal from noise on a
    // high-variance endpoint the way it can on a stable one. CV (coefficient
    // of variation = stdDev / avg) is the same noise measure Diagnosis
    // already uses elsewhere, reused here for consistency.
    private long minSamplesFor(Double beforeAvg, Double beforeStdDev) {
        if (beforeAvg == null || beforeStdDev == null || beforeAvg == 0.0) {
            return MIN_AFTER_SAMPLES_LOW_NOISE; // no noise data (old row, or before-avg was 0) — fall back
        }
        double cv = beforeStdDev / beforeAvg;
        if (cv >= 1.0) return MIN_AFTER_SAMPLES_HIGH_NOISE;
        if (cv >= 0.5) return MIN_AFTER_SAMPLES_MEDIUM_NOISE;
        return MIN_AFTER_SAMPLES_LOW_NOISE;
    }

    private FixComparisonDto toComparison(FixSnapshot snapshot) {
        List<Telemetry> afterRecords = fetchRecordsForEndpoint(
                snapshot.getApplicationName(), snapshot.getEndpoint(),
                snapshot.getMarkedFixedAt(), LocalDateTime.now());

        Map<String, Double> afterMetrics = EndpointMetrics.compute(afterRecords);
        long afterSampleCount = afterMetrics.containsKey("sampleCount") ? afterMetrics.get("sampleCount").longValue() : 0L;

        Double beforeAvg = snapshot.getBeforeAvgDurationMs();
        Double beforeStdDev = snapshot.getBeforeStdDeviationMs();
        long requiredSamples = minSamplesFor(beforeAvg, beforeStdDev);

        FixComparisonDto dto = new FixComparisonDto();
        dto.setId(snapshot.getId());
        dto.setApplicationName(snapshot.getApplicationName());
        dto.setEndpoint(snapshot.getEndpoint());
        dto.setRuleType(snapshot.getRuleType());
        dto.setNote(snapshot.getNote());
        dto.setMarkedFixedAt(snapshot.getMarkedFixedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        dto.setBeforeAvgDurationMs(beforeAvg);
        dto.setBeforeMaxQueryCount(snapshot.getBeforeMaxQueryCount());
        dto.setBeforeSampleCount(snapshot.getBeforeSampleCount());
        dto.setAfterSampleCount(afterSampleCount);

        if (afterSampleCount < requiredSamples) {
            dto.setStatus(FixSnapshot.Status.WATCHING.name());
            dto.setAfterAvgDurationMs(afterMetrics.get("avgDurationMs"));
            dto.setAfterMaxQueryCount(afterMetrics.get("maxQueryCount"));
            if (beforeStdDev != null && beforeAvg != null && beforeAvg > 0 && beforeStdDev / beforeAvg >= 0.5) {
                dto.setVerdictNote("This endpoint is naturally noisy (varies a lot request to request), "
                        + "so we're waiting for " + requiredSamples + " fresh samples instead of the usual "
                        + MIN_AFTER_SAMPLES_LOW_NOISE + " before giving a verdict.");
            }
            return dto;
        }

        double afterAvg = afterMetrics.getOrDefault("avgDurationMs", 0.0);
        dto.setAfterAvgDurationMs(afterAvg);
        dto.setAfterMaxQueryCount(afterMetrics.get("maxQueryCount"));

        if (beforeAvg == null || beforeAvg == 0.0) {
            dto.setStatus(FixSnapshot.Status.WATCHING.name());
            return dto;
        }

        double changeRatio = (beforeAvg - afterAvg) / beforeAvg; // positive = faster
        double improvementPercent = changeRatio * 100.0;

        // Noise-band gate: if we know the endpoint's pre-fix noise level,
        // the after-average must land outside [beforeAvg ± k*stdDev] before
        // we're willing to call it a real change at all — regardless of what
        // the raw percentage says. This is what would have caught the
        // /api/auth/register false positive (45% "improvement" that was
        // smaller than the endpoint's own stdDev).
        if (beforeStdDev != null && beforeStdDev > 0) {
            double band = beforeStdDev * NOISE_BAND_MULTIPLIER;
            boolean outsideBand = Math.abs(afterAvg - beforeAvg) > band;
            if (!outsideBand) {
                dto.setStatus(FixSnapshot.Status.NO_CHANGE.name());
                dto.setVerdictNote(String.format(
                        "The %.0f%% change is smaller than this endpoint's normal noise range "
                        + "(±%.0fms before the fix), so this isn't being counted as a real improvement or "
                        + "regression yet.", Math.abs(improvementPercent), band));
                return dto;
            }
        }

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

    // EndpointMetrics doesn't expose stdDev, so computed directly here from
    // raw durations — population stdDev, same formula DiagnosisService's own
    // regression/N+1 checks use elsewhere for consistency.
    private Double computeDurationStdDev(List<Telemetry> records) {
        if (records == null || records.isEmpty()) return null;
        double mean = records.stream().mapToLong(Telemetry::getDurationMs).average().orElse(0.0);
        double variance = records.stream()
                .mapToDouble(t -> Math.pow(t.getDurationMs() - mean, 2))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }

    // Genuinely deletes the fix record — distinct from the reopen behavior
    // above, which just changes status while keeping the row (that's correct
    // for a real fix that later regressed — you want that history). This is
    // for the different case of an accidental/wrong click that never should
    // have been marked at all; nothing else in the app could remove one
    // (checked AdminService's reset endpoints — they never touched this table).
    public long deleteFixSnapshot(String applicationName, String endpoint, String ruleType) {
        return fixSnapshotRepository.deleteByApplicationNameAndEndpointAndRuleType(
                applicationName, endpoint, ruleType);
    }
}