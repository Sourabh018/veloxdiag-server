package com.veloxdiag.server.service;

import com.veloxdiag.server.diagnosis.TelemetryWindowSettings;
import com.veloxdiag.server.entity.ConnectionPoolMetrics;
import com.veloxdiag.server.repository.ConnectionPoolMetricsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConnectionPoolMetricsService {

    private final ConnectionPoolMetricsRepository connectionPoolMetricsRepository;
    private final TelemetryWindowSettings windowSettings;

    public ConnectionPoolMetricsService(ConnectionPoolMetricsRepository connectionPoolMetricsRepository,
                                         TelemetryWindowSettings windowSettings) {
        this.connectionPoolMetricsRepository = connectionPoolMetricsRepository;
        this.windowSettings = windowSettings;
    }

    public ConnectionPoolMetrics save(ConnectionPoolMetrics metrics) {
        return connectionPoolMetricsRepository.save(metrics);
    }

    public List<ConnectionPoolMetrics> getHistory(String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        return connectionPoolMetricsRepository.findByApplicationNameAndTimestampAfterOrderByTimestampAsc(applicationName, cutoff);
    }

    public ConnectionPoolMetrics getLatest(String applicationName) {
        return connectionPoolMetricsRepository.findTopByApplicationNameOrderByTimestampDesc(applicationName);
    }
}