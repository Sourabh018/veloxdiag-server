package com.veloxdiag.server.dashboard;

public class SlowEndpointDTO {
    private String endpoint;
    private double avgDuration;
    private long count;

    public SlowEndpointDTO(String endpoint, double avgDuration, long count) {
        this.endpoint = endpoint;
        this.avgDuration = avgDuration;
        this.count = count;
    }

    public String getEndpoint() { return endpoint; }
    public double getAvgDuration() { return avgDuration; }
    public long getCount() { return count; }
}