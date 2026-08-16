package com.veloxdiag.server.service;

import com.veloxdiag.server.diagnosis.TelemetryWindowSettings;
import com.veloxdiag.server.entity.JvmMetrics;
import com.veloxdiag.server.repository.JvmMetricsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class JvmMetricsService {

    private final JvmMetricsRepository jvmMetricsRepository;
    private final TelemetryWindowSettings windowSettings;

    public JvmMetricsService(JvmMetricsRepository jvmMetricsRepository, TelemetryWindowSettings windowSettings) {
        this.jvmMetricsRepository = jvmMetricsRepository;
        this.windowSettings = windowSettings;
    }

    public JvmMetrics save(JvmMetrics metrics) {
        return jvmMetricsRepository.save(metrics);
    }

    // Same lookback window as the rest of the dashboard (TelemetryWindowSettings)
    // rather than a separate JVM-specific setting — keeps "last N days" meaning
    // one consistent thing across every chart on the page.
    public List<JvmMetrics> getHistory(String applicationName) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowSettings.getLookbackDays());
        return jvmMetricsRepository.findByApplicationNameAndTimestampAfterOrderByTimestampAsc(applicationName, cutoff);
    }

    public JvmMetrics getLatest(String applicationName) {
        return jvmMetricsRepository.findTopByApplicationNameOrderByTimestampDesc(applicationName);
    }
}