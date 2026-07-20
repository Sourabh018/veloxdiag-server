package com.veloxdiag.server.dashboard;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.veloxdiag.server.diagnosis.DiagnosisService;
import com.veloxdiag.server.diagnosis.EndpointNormalizer;
import com.veloxdiag.server.diagnosis.TelemetryWindowSettings;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import com.veloxdiag.server.repository.TelemetryRepository.SummaryProjection;

@Service
public class DashboardService {

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;
    private final DiagnosisService diagnosisService;

    public DashboardService(TelemetryRepository telemetryRepository, TelemetryWindowSettings windowSettings,
                             DiagnosisService diagnosisService) {
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
        this.diagnosisService = diagnosisService;
    }

    public DashboardSummary getSummary() {
        // Was 4 separate queries (getTotalRequests, getAverageResponseTime,
        // getErrorRequests, getConnectedApplications) — now 1.
        SummaryProjection stats = telemetryRepository.getSummaryStats();

        long totalRequests = stats.getTotalRequests() == null ? 0L : stats.getTotalRequests();
        double averageResponseTime = stats.getAverageResponseTime() == null ? 0.0 : stats.getAverageResponseTime();
        long errorRequests = stats.getErrorRequests() == null ? 0L : stats.getErrorRequests();
        long connectedApplications = stats.getConnectedApplications() == null ? 0L : stats.getConnectedApplications();

        return new DashboardSummary(
                totalRequests,
                averageResponseTime,
                errorRequests,
                connectedApplications
        );
    }

    public List<Telemetry> getRecent(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return telemetryRepository.findAllByOrderByTimestampDesc(pageable);
    }

    public List<Telemetry> getErrors(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return telemetryRepository.findByStatusGreaterThanEqualOrderByTimestampDesc(400, pageable);
    }

    // Was a single SQL-level "GROUP BY t.endpoint" query — but that groups by the
    // raw endpoint string, so /api/exams/{uuid-A} and /api/exams/{uuid-B} showed
    // up as separate rows with sampleCount 1-2 each instead of being combined
    // into one /api/exams/{id} row. EndpointNormalizer can't run inside JPQL, so
    // grouping has to happen in Java instead — same pattern DiagnosisService
    // already uses for its own endpoint grouping.
    //
    // Also now respects the shared lookback window (TelemetryWindowSettings),
    // consistent with Diagnosis, Query Analyzer, and Index Advisor — this page
    // previously scanned all-time data regardless of the Settings window,
    // which could show stale endpoints that haven't been slow in weeks.
    //
    // And now actually filters by the live slow-request threshold instead of
    // just returning the top N slowest endpoints unconditionally — previously
    // endpoints well under the threshold (e.g. 394ms against a 1000ms threshold)
    // still showed up here, contradicting the page's own header text
    // ("Endpoints averaging above the slow-request threshold").
    public List<SlowEndpointDTO> getSlowEndpoints(int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> recent = telemetryRepository.findByTimestampAfter(cutoff);
        double threshold = diagnosisService.getSlowRequestThresholdMs();

        Map<String, List<Telemetry>> byEndpoint = recent.stream()
                .collect(Collectors.groupingBy(t -> EndpointNormalizer.normalize(t.getEndpoint())));

        return byEndpoint.entrySet().stream()
                .map(e -> {
                    String endpoint = e.getKey();
                    List<Telemetry> records = e.getValue();
                    double avgDuration = records.stream()
                            .mapToLong(Telemetry::getDurationMs)
                            .average()
                            .orElse(0.0);
                    return new SlowEndpointDTO(endpoint, avgDuration, (long) records.size());
                })
                .filter(dto -> dto.getAvgDuration() >= threshold)
                .sorted(Comparator.comparingDouble(SlowEndpointDTO::getAvgDuration).reversed())
                .limit(limit)
                .toList();
    }

    public List<TrendPointDTO> getTrends(int hours) {
        List<Object[]> rows = telemetryRepository.findHourlyTrends(hours);
        return rows.stream()
                .map(r -> new TrendPointDTO(
                        (String) r[0],
                        ((Number) r[1]).longValue(),
                        r[2] == null ? 0.0 : ((Number) r[2]).doubleValue(),
                        ((Number) r[3]).longValue()
                ))
                .toList();
    }
}