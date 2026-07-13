package com.veloxdiag.server.dashboard;

import org.springframework.stereotype.Service;

import com.veloxdiag.server.repository.TelemetryRepository;

@Service
public class DashboardService {

    private final TelemetryRepository telemetryRepository;

    public DashboardService(TelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    public DashboardSummary getSummary() {

        long totalRequests = telemetryRepository.getTotalRequests();

        Double avg = telemetryRepository.getAverageResponseTime();
        double averageResponseTime = (avg == null) ? 0.0 : avg;

        long errorRequests = telemetryRepository.getErrorRequests();

        long connectedApplications =
                telemetryRepository.getConnectedApplications();

        return new DashboardSummary(
                totalRequests,
                averageResponseTime,
                errorRequests,
                connectedApplications
        );
    }
}