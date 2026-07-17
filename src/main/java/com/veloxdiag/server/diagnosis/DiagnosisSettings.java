package com.veloxdiag.server.diagnosis;

public class DiagnosisSettings {

    private double slowRequestThresholdMs;
    private long highErrorRateThreshold;
    private int serverErrorStatusThreshold;
    private int lookbackDays;

    public DiagnosisSettings() {
    }

    public DiagnosisSettings(double slowRequestThresholdMs, long highErrorRateThreshold,
                              int serverErrorStatusThreshold, int lookbackDays) {
        this.slowRequestThresholdMs = slowRequestThresholdMs;
        this.highErrorRateThreshold = highErrorRateThreshold;
        this.serverErrorStatusThreshold = serverErrorStatusThreshold;
        this.lookbackDays = lookbackDays;
    }

    public double getSlowRequestThresholdMs() { return slowRequestThresholdMs; }
    public void setSlowRequestThresholdMs(double slowRequestThresholdMs) { this.slowRequestThresholdMs = slowRequestThresholdMs; }

    public long getHighErrorRateThreshold() { return highErrorRateThreshold; }
    public void setHighErrorRateThreshold(long highErrorRateThreshold) { this.highErrorRateThreshold = highErrorRateThreshold; }

    public int getServerErrorStatusThreshold() { return serverErrorStatusThreshold; }
    public void setServerErrorStatusThreshold(int serverErrorStatusThreshold) { this.serverErrorStatusThreshold = serverErrorStatusThreshold; }

    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }
}