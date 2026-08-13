package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for FixTrackingService — the before/after verdict logic (IMPROVED /
 * REGRESSED / NO_CHANGE / WATCHING). This is the component behind the
 * product's most distinctive claim ("did your fix actually work"), and the
 * noise-band gate specifically exists because of a real false positive
 * (/api/auth/register showing a fake 45% "improvement" — see the service's
 * own code comment). These tests lock that behavior in.
 */
class FixTrackingServiceTest {

    private FixSnapshotRepository fixSnapshotRepository;
    private TelemetryRepository telemetryRepository;
    private TelemetryWindowSettings windowSettings;
    private FixTrackingService service;

    private static final String APP = "CET_CELL";
    private static final String ENDPOINT = "/api/exams/{id}";
    private static final String RULE_TYPE = "POSSIBLE_N_PLUS_ONE";

    @BeforeEach
    void setUp() {
        fixSnapshotRepository = mock(FixSnapshotRepository.class);
        telemetryRepository = mock(TelemetryRepository.class);
        windowSettings = mock(TelemetryWindowSettings.class);
        when(windowSettings.getLookbackDays(anyString())).thenReturn(7);
        service = new FixTrackingService(fixSnapshotRepository, telemetryRepository, windowSettings);
    }

    private Telemetry telemetry(long durationMs) {
        return new Telemetry(APP, ENDPOINT, "GET", 200, durationMs, LocalDateTime.now());
    }

    private List<Telemetry> uniform(int n, long durationMs) {
        List<Telemetry> records = new ArrayList<>();
        for (int i = 0; i < n; i++) records.add(telemetry(durationMs));
        return records;
    }

    /** Builds a FixSnapshot exactly as markAsFixed() would, without going through the repository mock. */
    private FixSnapshot snapshot(Double beforeAvg, Double beforeStdDev, long beforeSampleCount) {
        return new FixSnapshot(APP, ENDPOINT, RULE_TYPE, "test note", LocalDateTime.now(),
                beforeAvg, 5.0, beforeSampleCount, beforeStdDev);
    }

    // ---------- markAsFixed ----------

    @Test
    @DisplayName("markAsFixed captures current metrics and stdDev as the before-snapshot")
    void markAsFixedCapturesSnapshot() {
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(10, 3000));

        service.markAsFixed(APP, ENDPOINT, RULE_TYPE, "applied JOIN FETCH");

        verify(fixSnapshotRepository).save(argThatCapturesBefore());
    }

    private FixSnapshot argThatCapturesBefore() {
        return org.mockito.ArgumentMatchers.argThat(s ->
                s != null
                        && s.getApplicationName().equals(APP)
                        && s.getEndpoint().equals(ENDPOINT)
                        && s.getRuleType().equals(RULE_TYPE)
                        && s.getBeforeAvgDurationMs() != null
                        && s.getBeforeAvgDurationMs() == 3000.0
                        && s.getBeforeStdDeviationMs() != null
                        && s.getBeforeStdDeviationMs() == 0.0 // uniform durations -> zero stdDev
        );
    }

    // ---------- getComparisons: WATCHING (insufficient after-samples) ----------

    @Test
    @DisplayName("stays WATCHING when there aren't yet enough after-fix samples for a low-noise endpoint")
    void watchingWithTooFewAfterSamplesLowNoise() {
        // before: stable endpoint (low CV) -> requires only MIN_AFTER_SAMPLES_LOW_NOISE (5)
        FixSnapshot snap = snapshot(3000.0, 100.0, 20L); // CV = 100/3000 ≈ 0.033, low noise
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(3, 1000)); // only 3 after-samples, need 5

        List<FixComparisonDto> comparisons = service.getComparisons(APP);

        assertThat(comparisons).hasSize(1);
        assertThat(comparisons.get(0).getStatus()).isEqualTo("WATCHING");
    }

    @Test
    @DisplayName("requires more after-samples on a noisy (high pre-fix variance) endpoint before giving a verdict")
    void watchingRequiresMoreSamplesOnNoisyEndpoint() {
        // beforeAvg=3000, beforeStdDev=3500 -> CV ≈ 1.17 -> HIGH noise tier -> needs 20 after-samples
        FixSnapshot snap = snapshot(3000.0, 3500.0, 20L);
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        // 15 after-samples: enough for medium-noise tier (10) but not high-noise tier (20)
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(15, 500));

        List<FixComparisonDto> comparisons = service.getComparisons(APP);

        assertThat(comparisons.get(0).getStatus()).isEqualTo("WATCHING");
        assertThat(comparisons.get(0).getVerdictNote()).containsIgnoringCase("noisy");
    }

    // ---------- getComparisons: real verdicts ----------

    @Test
    @DisplayName("reports IMPROVED when the after-average is a real, noise-clearing improvement")
    void reportsImprovedForRealImprovement() {
        // before: 3000ms avg, low stdDev (100) -> small noise band, easy to clear
        FixSnapshot snap = snapshot(3000.0, 100.0, 20L);
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        // after: consistently 800ms -> ~73% faster, way outside the noise band and threshold
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(10, 800));

        List<FixComparisonDto> comparisons = service.getComparisons(APP);

        FixComparisonDto dto = comparisons.get(0);
        assertThat(dto.getStatus()).isEqualTo("IMPROVED");
        assertThat(dto.getImprovementPercent()).isGreaterThan(10.0);
    }

    @Test
    @DisplayName("reports REGRESSED when the after-average is meaningfully slower")
    void reportsRegressedWhenSlower() {
        FixSnapshot snap = snapshot(1000.0, 50.0, 20L);
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        // after: 1500ms -> 50% slower, well outside noise band and past the 5% regression floor
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(10, 1500));

        List<FixComparisonDto> comparisons = service.getComparisons(APP);
        assertThat(comparisons.get(0).getStatus()).isEqualTo("REGRESSED");
    }

    @Test
    @DisplayName("does NOT report IMPROVED for a change that is real percentage-wise but inside the endpoint's own noise band (the /api/auth/register false-positive case)")
    void guardsAgainstFalsePositiveWithinNoiseBand() {
        // Reproduces the exact false-positive from the code's own comment:
        // beforeAvg=1339, beforeStdDev=1690 (CV > 1, very noisy), after=732 -> a 45% raw
        // improvement, but smaller than the endpoint's own stdDev -> should NOT be IMPROVED.
        FixSnapshot snap = snapshot(1339.0, 1690.0, 20L);
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        // CV = 1690/1339 ≈ 1.26 -> HIGH noise tier -> needs 20 after-samples
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(20, 732));

        List<FixComparisonDto> comparisons = service.getComparisons(APP);

        FixComparisonDto dto = comparisons.get(0);
        assertThat(dto.getStatus()).isNotEqualTo("IMPROVED");
        assertThat(dto.getStatus()).isEqualTo("NO_CHANGE");
        assertThat(dto.getVerdictNote()).containsIgnoringCase("noise");
    }

    @Test
    @DisplayName("reports NO_CHANGE when the after-average is essentially the same as before")
    void reportsNoChangeWhenStable() {
        FixSnapshot snap = snapshot(1000.0, 50.0, 20L);
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        // after: 1020ms -> 2% change, inside both the noise band and the improvement/regression floors
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(10, 1020));

        List<FixComparisonDto> comparisons = service.getComparisons(APP);
        assertThat(comparisons.get(0).getStatus()).isEqualTo("NO_CHANGE");
    }

    @Test
    @DisplayName("falls back to the fixed low-noise sample minimum when no before-stdDev was captured (legacy row)")
    void fallsBackToLowNoiseMinimumWithoutBeforeStdDev() {
        FixSnapshot snap = snapshot(2000.0, null, 20L); // no stdDev captured
        when(fixSnapshotRepository.findByApplicationNameOrderByMarkedFixedAtDesc(APP)).thenReturn(List.of(snap));
        when(telemetryRepository.findByApplicationNameAndTimestampBetween(any(), any(), any()))
                .thenReturn(uniform(5, 1000)); // exactly MIN_AFTER_SAMPLES_LOW_NOISE (5)

        List<FixComparisonDto> comparisons = service.getComparisons(APP);
        // 5 samples should be enough under the low-noise fallback; without the noise band
        // (beforeStdDev is null) the raw 50% improvement should be honored directly
        assertThat(comparisons.get(0).getStatus()).isEqualTo("IMPROVED");
    }

    @Test
    @DisplayName("getComparisons(null) reads across all applications instead of filtering to one")
    void nullApplicationNameReadsAllApps() {
        FixSnapshot snap = snapshot(1000.0, 50.0, 20L);
        when(fixSnapshotRepository.findAllByOrderByMarkedFixedAtDesc()).thenReturn(List.of(snap));
        when(telemetryRepository.findByTimestampBetween(any(), any())).thenReturn(uniform(10, 1020));

        List<FixComparisonDto> comparisons = service.getComparisons(null);

        assertThat(comparisons).hasSize(1);
        verify(fixSnapshotRepository).findAllByOrderByMarkedFixedAtDesc();
    }
}