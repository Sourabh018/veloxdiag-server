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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for IndexAdvisorService — the heuristic ("slow on every call, not
 * just under load") signal that is explicitly weaker evidence than the
 * EXPLAIN-based MISSING_INDEX_CANDIDATE finding in DiagnosisService (its own
 * generated message says so). These tests lock in the two knobs that make
 * that distinction meaningful: the average-duration floor and the
 * low-variance ceiling.
 */
class IndexAdvisorServiceTest {

    private TelemetryRepository telemetryRepository;
    private TelemetryWindowSettings windowSettings;
    private IndexAdvisorService service;

    private static final String ENDPOINT = "/api/exams/{id}";

    @BeforeEach
    void setUp() {
        telemetryRepository = mock(TelemetryRepository.class);
        windowSettings = mock(TelemetryWindowSettings.class);
        when(windowSettings.getLookbackDays(any())).thenReturn(7);
        service = new IndexAdvisorService(telemetryRepository, windowSettings);
    }

    private Telemetry telemetry(String endpoint, long durationMs) {
        return new Telemetry("CET_CELL", endpoint, "GET", 200, durationMs, LocalDateTime.now());
    }

    private List<Telemetry> uniform(String endpoint, int n, long durationMs) {
        List<Telemetry> records = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            records.add(telemetry(endpoint, durationMs));
        }
        return records;
    }

    @Test
    @DisplayName("does not flag an endpoint with too few samples, even if slow and consistent")
    void noFindingWithFewSamples() {
        // MIN_SAMPLE_COUNT is 6
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(uniform(ENDPOINT, 3, 5000));
        List<IndexAdvisorFinding> candidates = service.analyzeCandidates();
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("does not flag a fast endpoint even with zero variance")
    void noFindingWhenFastRegardlessOfVariance() {
        // below default 1000ms floor
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(uniform(ENDPOINT, 10, 200));
        List<IndexAdvisorFinding> candidates = service.analyzeCandidates();
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("does not flag a slow endpoint with high variance (load-dependent, not consistently slow)")
    void noFindingWhenSlowButHighVariance() {
        List<Telemetry> records = new ArrayList<>();
        // wildly varying durations around a slow average -> high coefficient of variation
        long[] durations = {200, 300, 8000, 400, 9000, 250, 7500, 300, 8200, 350};
        for (long d : durations) {
            records.add(telemetry(ENDPOINT, d));
        }
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(records);

        List<IndexAdvisorFinding> candidates = service.analyzeCandidates();
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("flags an endpoint that is both slow AND consistently slow (low variance)")
    void flagsSlowAndConsistentEndpoint() {
        // all requests exactly 3000ms -> zero variance, well above the 1000ms floor
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(uniform(ENDPOINT, 10, 3000));

        List<IndexAdvisorFinding> candidates = service.analyzeCandidates();

        assertThat(candidates).hasSize(1);
        IndexAdvisorFinding finding = candidates.get(0);
        assertThat(finding.getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(finding.getAvgDurationMs()).isEqualTo(3000.0);
        assertThat(finding.getCoefficientOfVariation()).isEqualTo(0.0);
        // the generated message must hedge, never overclaim a confirmed diagnosis
        assertThat(finding.getMessage()).containsIgnoringCase("not confirmed");
    }

    @Test
    @DisplayName("respects a per-application configured duration threshold instead of the hardcoded default")
    void respectsPerApplicationThreshold() {
        String app = "CET_CELL";
        service.setMinAvgDurationMs(app, 5000.0); // raise the bar well above 3000ms

        when(telemetryRepository.findByApplicationNameAndTimestampAfter(any(), any()))
                .thenReturn(uniform(ENDPOINT, 10, 3000));

        List<IndexAdvisorFinding> candidates = service.analyzeCandidates(app);

        // 3000ms average no longer clears this app's custom 5000ms floor
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("respects a per-application configured variance threshold")
    void respectsPerApplicationVarianceThreshold() {
        String app = "CET_CELL";

        List<Telemetry> records = new ArrayList<>();
        // avg = 3000ms, stdDev = 900ms -> CV = 0.30: fails the default 0.20
        // ceiling but should pass once this app's threshold is widened to 0.5.
        long[] durations = {2100, 3900, 2100, 3900, 2100, 3900, 2100, 3900, 2100, 3900};
        for (long d : durations) {
            records.add(telemetry(ENDPOINT, d));
        }
        when(telemetryRepository.findByApplicationNameAndTimestampAfter(any(), any())).thenReturn(records);

        List<IndexAdvisorFinding> defaultResult = service.analyzeCandidates(app);
        assertThat(defaultResult).isEmpty(); // CV 0.30 > default 0.20 ceiling

        service.setLowVarianceThreshold(app, 0.5);
        List<IndexAdvisorFinding> widenedResult = service.analyzeCandidates(app);
        assertThat(widenedResult).hasSize(1); // CV 0.30 <= widened 0.5 ceiling
    }

    @Test
    @DisplayName("returns multiple candidates sorted by average duration, slowest first")
    void sortsMultipleCandidatesBySlowestFirst() {
        List<Telemetry> records = new ArrayList<>();
        records.addAll(uniform("/api/results/{id}", 8, 2000));
        records.addAll(uniform("/api/exams/my", 8, 6000));
        records.addAll(uniform("/api/topics", 8, 4000));
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(records);

        List<IndexAdvisorFinding> candidates = service.analyzeCandidates();

        assertThat(candidates).hasSize(3);
        assertThat(candidates.get(0).getEndpoint()).isEqualTo("/api/exams/my");
        assertThat(candidates.get(1).getEndpoint()).isEqualTo("/api/topics");
        assertThat(candidates.get(2).getEndpoint()).isEqualTo("/api/results/{id}");
    }

    @Test
    @DisplayName("does not throw and returns empty when there is no telemetry at all")
    void noThrowOnEmptyTelemetry() {
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(List.of());
        List<IndexAdvisorFinding> candidates = service.analyzeCandidates();
        assertThat(candidates).isEmpty();
    }
}