package com.veloxdiag.server.diagnosis;

public class DiagnosisSettings {

    private double slowRequestThresholdMs;
    private long highErrorRateThreshold;
    private int serverErrorStatusThreshold;
    private int lookbackDays;
    private long possibleNPlusOneQueryThreshold;
    private long seqScanRowThreshold;
    private double minAvgDurationMs;
    private double lowVarianceThreshold;

    public DiagnosisSettings() {
    }

    public DiagnosisSettings(double slowRequestThresholdMs, long highErrorRateThreshold,
                              int serverErrorStatusThreshold, int lookbackDays,
                              long possibleNPlusOneQueryThreshold, long seqScanRowThreshold,
                              double minAvgDurationMs, double lowVarianceThreshold) {
        this.slowRequestThresholdMs = slowRequestThresholdMs;
        this.highErrorRateThreshold = highErrorRateThreshold;
        this.serverErrorStatusThreshold = serverErrorStatusThreshold;
        this.lookbackDays = lookbackDays;
        this.possibleNPlusOneQueryThreshold = possibleNPlusOneQueryThreshold;
        this.seqScanRowThreshold = seqScanRowThreshold;
        this.minAvgDurationMs = minAvgDurationMs;
        this.lowVarianceThreshold = lowVarianceThreshold;
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

    public long getSeqScanRowThreshold() { return seqScanRowThreshold; }
    public void setSeqScanRowThreshold(long seqScanRowThreshold) { this.seqScanRowThreshold = seqScanRowThreshold; }

    public double getMinAvgDurationMs() { return minAvgDurationMs; }
    public void setMinAvgDurationMs(double minAvgDurationMs) { this.minAvgDurationMs = minAvgDurationMs; }

    public double getLowVarianceThreshold() { return lowVarianceThreshold; }
    public void setLowVarianceThreshold(double lowVarianceThreshold) { this.lowVarianceThreshold = lowVarianceThreshold; }
}