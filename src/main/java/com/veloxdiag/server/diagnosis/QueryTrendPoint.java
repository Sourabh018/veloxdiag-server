package com.veloxdiag.server.diagnosis;

import java.time.LocalDate;

public class QueryTrendPoint {

    private LocalDate date;
    private double avgDurationMs;
    private long sampleCount;

    public QueryTrendPoint(LocalDate date, double avgDurationMs, long sampleCount) {
        this.date = date;
        this.avgDurationMs = avgDurationMs;
        this.sampleCount = sampleCount;
    }

    public LocalDate getDate() { return date; }
    public double getAvgDurationMs() { return avgDurationMs; }
    public long getSampleCount() { return sampleCount; }
}