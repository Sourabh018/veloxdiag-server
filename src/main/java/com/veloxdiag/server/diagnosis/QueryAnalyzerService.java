package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueryAnalyzerService {

    // A trend needs at least this many distinct days of data to be meaningful
    private static final int MIN_DAYS_FOR_TREND = 2;

    // Percent change beyond this is flagged as a real trend, not noise
    private static final double SIGNIFICANT_CHANGE_THRESHOLD = 15.0;

    private final TelemetryRepository telemetryRepository;

    public QueryAnalyzerService(TelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    public List<EndpointTrend> analyzeTrends() {
        List<Telemetry> all = telemetryRepository.findAll();

        Map<String, List<Telemetry>> byEndpoint = all.stream()
                .collect(Collectors.groupingBy(Telemetry::getEndpoint));

        List<EndpointTrend> trends = new ArrayList<>();

        for (Map.Entry<String, List<Telemetry>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<Telemetry> records = entry.getValue();

            Map<LocalDate, List<Telemetry>> byDay = records.stream()
                    .collect(Collectors.groupingBy(t -> t.getTimestamp().toLocalDate()));

            if (byDay.size() < MIN_DAYS_FOR_TREND) {
                continue; // not enough history to call it a trend
            }

            List<LocalDate> sortedDays = byDay.keySet().stream().sorted().collect(Collectors.toList());

            List<QueryTrendPoint> points = sortedDays.stream()
                    .map(day -> {
                        List<Telemetry> dayRecords = byDay.get(day);
                        double avg = dayRecords.stream().mapToLong(Telemetry::getDurationMs).average().orElse(0.0);
                        return new QueryTrendPoint(day, avg, dayRecords.size());
                    })
                    .collect(Collectors.toList());

            double firstAvg = points.get(0).getAvgDurationMs();
            double latestAvg = points.get(points.size() - 1).getAvgDurationMs();

            double percentChange = firstAvg == 0
                    ? 0.0
                    : ((latestAvg - firstAvg) / firstAvg) * 100.0;

            String direction;
            if (percentChange > SIGNIFICANT_CHANGE_THRESHOLD) {
                direction = "WORSENING";
            } else if (percentChange < -SIGNIFICANT_CHANGE_THRESHOLD) {
                direction = "IMPROVING";
            } else {
                direction = "STABLE";
            }

            trends.add(new EndpointTrend(endpoint, points, direction, percentChange, firstAvg, latestAvg));
        }

        // Worst regressions first
        trends.sort((a, b) -> Double.compare(b.getPercentChange(), a.getPercentChange()));

        return trends;
    }
}