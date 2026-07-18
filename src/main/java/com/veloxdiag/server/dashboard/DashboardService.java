package com.veloxdiag.server.dashboard;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import com.veloxdiag.server.repository.TelemetryRepository.SlowEndpointProjection;
import com.veloxdiag.server.repository.TelemetryRepository.SummaryProjection;

@Service
public class DashboardService {

    private final TelemetryRepository telemetryRepository;

    public DashboardService(TelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
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

    public List<SlowEndpointDTO> getSlowEndpoints(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<SlowEndpointProjection> rows = telemetryRepository.findSlowEndpoints(pageable);
        return rows.stream()
                .map(r -> new SlowEndpointDTO(r.getEndpoint(), r.getAvgDuration(), r.getCount()))
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