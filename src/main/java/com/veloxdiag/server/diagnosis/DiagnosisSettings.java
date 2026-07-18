package com.veloxdiag.server.diagnosis;

public class DiagnosisSettings {

    private double slowRequestThresholdMs;
    private long highErrorRateThreshold;
    private int serverErrorStatusThreshold;
    private int lookbackDays;
    private long possibleNPlusOneQueryThreshold;

    public DiagnosisSettings() {
    }

    public DiagnosisSettings(double slowRequestThresholdMs, long highErrorRateThreshold,
                              int serverErrorStatusThreshold, int lookbackDays,
                              long possibleNPlusOneQueryThreshold) {
        this.slowRequestThresholdMs = slowRequestThresholdMs;
        this.highErrorRateThreshold = highErrorRateThreshold;
        this.serverErrorStatusThreshold = serverErrorStatusThreshold;
        this.lookbackDays = lookbackDays;
        this.possibleNPlusOneQueryThreshold = possibleNPlusOneQueryThreshold;
    }

    public double getSlowRequestThresholdMs() { return slowRequestThresholdMs; }
    public void setSlowRequestThresholdMs(double slowRequestThresholdMs) { this.slowRequestThresholdMs = slowRequestThresholdMs; }

    public long getHighErrorRateThreshold() { return highErrorRateThreshold; }
    public void setHighErrorRateThreshold(long highErrorRateThreshold) { this.highErrorRateThreshold = highErrorRateThreshold; }

    public int getServerErrorStatusThreshold() { return serverErrorStatusThreshold; }
    public void setServerErrorStatusThreshold(int serverErrorStatusThreshold) { this.serverErrorStatusThreshold = serverErrorStatusThreshold; }

    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }

    public long getPossibleNPlusOneQueryThreshold() { return possibleNPlusOneQueryThreshold; }
    public void setPossibleNPlusOneQueryThreshold(long possibleNPlusOneQueryThreshold) { this.possibleNPlusOneQueryThreshold = possibleNPlusOneQueryThreshold; }
}