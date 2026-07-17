package com.veloxdiag.server.diagnosis;

import java.util.List;

public class EndpointTrend {

    private String endpoint;
    private List<QueryTrendPoint> points;
    private String trendDirection; // "WORSENING", "IMPROVING", "STABLE"
    private double percentChange;
    private double firstAvgMs;
    private double latestAvgMs;

    public EndpointTrend(String endpoint, List<QueryTrendPoint> points, String trendDirection,
                          double percentChange, double firstAvgMs, double latestAvgMs) {
        this.endpoint = endpoint;
        this.points = points;
        this.trendDirection = trendDirection;
        this.percentChange = percentChange;
        this.firstAvgMs = firstAvgMs;
        this.latestAvgMs = latestAvgMs;
    }

    public String getEndpoint() { return endpoint; }
    public List<QueryTrendPoint> getPoints() { return points; }
    public String getTrendDirection() { return trendDirection; }
    public double getPercentChange() { return percentChange; }
    public double getFirstAvgMs() { return firstAvgMs; }
    public double getLatestAvgMs() { return latestAvgMs; }
}