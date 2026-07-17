package com.veloxdiag.server.diagnosis;

public class IndexAdvisorFinding {

    private String endpoint;
    private double avgDurationMs;
    private double stdDeviationMs;
    private double coefficientOfVariation; // stdDev / avg — lower means more consistent
    private long sampleCount;
    private String message;

    public IndexAdvisorFinding(String endpoint, double avgDurationMs, double stdDeviationMs,
                                double coefficientOfVariation, long sampleCount, String message) {
        this.endpoint = endpoint;
        this.avgDurationMs = avgDurationMs;
        this.stdDeviationMs = stdDeviationMs;
        this.coefficientOfVariation = coefficientOfVariation;
        this.sampleCount = sampleCount;
        this.message = message;
    }

    public String getEndpoint() { return endpoint; }
    public double getAvgDurationMs() { return avgDurationMs; }
    public double getStdDeviationMs() { return stdDeviationMs; }
    public double getCoefficientOfVariation() { return coefficientOfVariation; }
    public long getSampleCount() { return sampleCount; }
    public String getMessage() { return message; }
}