package com.veloxdiag.server.dashboard;

public class TrendPointDTO {
    private String bucket;
    private long requestCount;
    private double avgDuration;
    private long errorCount;

    public TrendPointDTO(String bucket, long requestCount, double avgDuration, long errorCount) {
        this.bucket = bucket;
        this.requestCount = requestCount;
        this.avgDuration = avgDuration;
        this.errorCount = errorCount;
    }

    public String getBucket() { return bucket; }
    public long getRequestCount() { return requestCount; }
    public double getAvgDuration() { return avgDuration; }
    public long getErrorCount() { return errorCount; }
}