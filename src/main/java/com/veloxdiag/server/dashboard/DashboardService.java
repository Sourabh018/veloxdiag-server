package com.veloxdiag.server.dashboard;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.veloxdiag.server.diagnosis.DiagnosisFinding;
import com.veloxdiag.server.diagnosis.DiagnosisService;
import com.veloxdiag.server.diagnosis.EndpointNormalizer;
import com.veloxdiag.server.diagnosis.TelemetryWindowSettings;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import com.veloxdiag.server.repository.TelemetryRepository.SummaryProjection;

@Service
public class DashboardService {

    // Weighted point deduction per active finding, by severity. Chosen to be
    // simple and explainable in one sentence (no hidden ML/black-box scoring):
    // a single HIGH finding meaningfully dents the score, a handful of LOWs
    // barely move it. Floored at 0 so a very broken app doesn't go negative.
    private static final int HIGH_PENALTY = 15;
    private static final int MEDIUM_PENALTY = 7;
    private static final int LOW_PENALTY = 2;
    private static final int STARTING_SCORE = 100;
    private static final int MAX_DEDUCTION_PER_ENDPOINT = 30;

    private final TelemetryRepository telemetryRepository;
    private final TelemetryWindowSettings windowSettings;
    private final DiagnosisService diagnosisService;
    private final com.veloxdiag.server.auth.ApplicationRepository applicationRepository;

    public DashboardService(TelemetryRepository telemetryRepository, TelemetryWindowSettings windowSettings,
                             DiagnosisService diagnosisService,
                             com.veloxdiag.server.auth.ApplicationRepository applicationRepository) {
        this.telemetryRepository = telemetryRepository;
        this.windowSettings = windowSettings;
        this.diagnosisService = diagnosisService;
        this.applicationRepository = applicationRepository;
    }

    public DashboardSummary getSummary() {
        return buildSummary(telemetryRepository.getSummaryStats(), computeHealthScore(null));
    }

    // App-selector-scoped version. Blank/null applicationName falls back to the
    // combined "All Apps" view, same as getSummary() above.
    public DashboardSummary getSummary(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return getSummary();
        }
        return buildSummary(telemetryRepository.getSummaryStatsByApplication(applicationName), computeHealthScore(applicationName));
    }

    private DashboardSummary buildSummary(TelemetryRepository.SummaryProjection stats, int healthScore) {
        long totalRequests = stats.getTotalRequests() == null ? 0L : stats.getTotalRequests();
        double averageResponseTime = stats.getAverageResponseTime() == null ? 0.0 : stats.getAverageResponseTime();
        long errorRequests = stats.getErrorRequests() == null ? 0L : stats.getErrorRequests();
        long connectedApplications = stats.getConnectedApplications() == null ? 0L : stats.getConnectedApplications();

        return new DashboardSummary(
                totalRequests,
                averageResponseTime,
                errorRequests,
                connectedApplications,
                healthScore
        );
    }

    // Scoped to the authenticated user's own registered applications (see
    // Application registry, ApplicationController) — this is what makes the
    // app-selector dropdown per-user instead of showing every application on
    // the server. MIGRATION FALLBACK: if the current user has zero registered
    // applications (e.g. before anyone has registered CET_CELL via
    // POST /api/applications), falls back to the old "every distinct
    // application name seen in telemetry" behavior — same reasoning as
    // AppOwnershipFilter's backward-compatible default, so the dropdown isn't
    // suddenly empty for everyone the moment this ships. Once real
    // applications are registered, this returns only the current user's own.
    public List<String> getApplications() {
        com.veloxdiag.server.auth.User current = com.veloxdiag.server.auth.CurrentUserContext.get();
        if (current != null) {
            List<String> owned = applicationRepository.findByOwnerUserId(current.getId()).stream()
                    .map(com.veloxdiag.server.auth.Application::getName)
                    .collect(Collectors.toList());
            if (!owned.isEmpty()) {
                return owned;
            }
        }
        return telemetryRepository.findDistinctApplicationNames();
    }

    // Averages per-endpoint deduction weighted by each unhealthy endpoint's
    // share of traffic AMONG unhealthy endpoints — not against total app
    // traffic. Weighting against total traffic was tried and rejected: with
    // ~720 of 864 requests going to endpoints with zero findings, dividing by
    // ALL traffic let those healthy requests dilute the score even further
    // (93 -> ~97) instead of fixing the original dilution-by-endpoint-count
    // bug. Weighting only within the unhealthy set means a broken endpoint
    // that gets hit constantly (e.g. a confirmed 3.1x-slower N+1 on the
    // app's busiest exam-taking endpoint) correctly drags the score down
    // more than an equally-severe but rarely-called broken endpoint, while
    // healthy endpoints — however much total traffic they carry — are
    // excluded from the average entirely rather than papering over real
    // problems just by existing in large numbers.
    private int computeHealthScore(String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> recent = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampAfter(cutoff)
                : telemetryRepository.findByApplicationNameAndTimestampAfter(applicationName, cutoff);

        if (recent.isEmpty()) {
            return STARTING_SCORE;
        }

        Map<String, Long> requestCountByEndpoint = recent.stream()
                .collect(Collectors.groupingBy(
                        t -> EndpointNormalizer.normalize(t.getEndpoint()),
                        Collectors.counting()));

        List<DiagnosisFinding> findings = diagnosisService.runDiagnosis(applicationName);
        Map<String, List<DiagnosisFinding>> byEndpoint = findings.stream()
                .collect(Collectors.groupingBy(DiagnosisFinding::getEndpoint));

        if (byEndpoint.isEmpty()) {
            return STARTING_SCORE;
        }

        long unhealthyTrafficTotal = 0;
        int endpointCountFallback = 0; // used only if none of the unhealthy endpoints matched any telemetry
        Map<String, Integer> deductionByEndpoint = new java.util.HashMap<>();

        for (Map.Entry<String, List<DiagnosisFinding>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            int endpointDeduction = 0;
            for (DiagnosisFinding finding : entry.getValue()) {
                String severity = finding.getSeverity();
                if ("HIGH".equals(severity)) {
                    endpointDeduction += HIGH_PENALTY;
                } else if ("MEDIUM".equals(severity)) {
                    endpointDeduction += MEDIUM_PENALTY;
                } else if ("LOW".equals(severity)) {
                    endpointDeduction += LOW_PENALTY;
                }
            }
            endpointDeduction = Math.min(endpointDeduction, MAX_DEDUCTION_PER_ENDPOINT);
            deductionByEndpoint.put(endpoint, endpointDeduction);
            unhealthyTrafficTotal += requestCountByEndpoint.getOrDefault(endpoint, 0L);
            endpointCountFallback++;
        }

        double weightedDeduction;
        if (unhealthyTrafficTotal > 0) {
            double sum = 0.0;
            for (Map.Entry<String, Integer> entry : deductionByEndpoint.entrySet()) {
                long endpointRequests = requestCountByEndpoint.getOrDefault(entry.getKey(), 0L);
                sum += entry.getValue() * ((double) endpointRequests / unhealthyTrafficTotal);
            }
            weightedDeduction = sum;
        } else {
            // Edge case: findings exist for endpoints with no matching telemetry
            // in this exact window (e.g. diagnosis ran over a slightly different
            // slice). Fall back to a plain average across those endpoints rather
            // than divide by zero.
            weightedDeduction = deductionByEndpoint.values().stream()
                    .mapToInt(Integer::intValue).average().orElse(0.0);
        }

        return Math.max(0, (int) Math.round(STARTING_SCORE - weightedDeduction));
    }

    public List<Telemetry> getRecent(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return telemetryRepository.findAllByOrderByTimestampDesc(pageable);
    }

    public List<Telemetry> getRecent(int limit, String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return getRecent(limit);
        }
        Pageable pageable = PageRequest.of(0, limit);
        return telemetryRepository.findByApplicationNameOrderByTimestampDesc(applicationName, pageable);
    }

    public List<Telemetry> getErrors(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return telemetryRepository.findByStatusGreaterThanEqualOrderByTimestampDesc(400, pageable);
    }

    public List<Telemetry> getErrors(int limit, String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return getErrors(limit);
        }
        Pageable pageable = PageRequest.of(0, limit);
        return telemetryRepository.findByApplicationNameAndStatusGreaterThanEqualOrderByTimestampDesc(applicationName, 400, pageable);
    }

    public List<SlowEndpointDTO> getSlowEndpoints(int limit) {
        return getSlowEndpoints(limit, null);
    }

    public List<SlowEndpointDTO> getSlowEndpoints(int limit, String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        List<Telemetry> recent = (applicationName == null || applicationName.isBlank())
                ? telemetryRepository.findByTimestampAfter(cutoff)
                : telemetryRepository.findByApplicationNameAndTimestampAfter(applicationName, cutoff);
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
        return getTrends(hours, null);
    }

    public List<TrendPointDTO> getTrends(int hours, String applicationName) {
        String appFilter = (applicationName == null || applicationName.isBlank()) ? null : applicationName;
        List<Object[]> rows = telemetryRepository.findHourlyTrends(hours, appFilter);
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