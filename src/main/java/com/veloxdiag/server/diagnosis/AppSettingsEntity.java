package com.veloxdiag.server.diagnosis;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-application persistence for DiagnosisSettings, so values survive server
 * restarts/redeploys instead of resetting to hardcoded defaults.
 *
 * One row per application, keyed by applicationName — CET_CELL and AgroMart
 * (and any future app) each get their own independently-tunable thresholds.
 * This replaces the earlier single-row-fixed-at-id=1L design.
 */
@Entity
@Table(name = "app_settings")
public class AppSettingsEntity {

    @Id
    private String applicationName;

    private double slowRequestThresholdMs;
    private long highErrorRateThreshold;
    private int serverErrorStatusThreshold;
    private int lookbackDays;
    private long possibleNPlusOneQueryThreshold;
    private long seqScanRowThreshold;
    private double minAvgDurationMs;
    private double lowVarianceThreshold;

    public AppSettingsEntity() {
    }

    public AppSettingsEntity(String applicationName, double slowRequestThresholdMs, long highErrorRateThreshold,
                              int serverErrorStatusThreshold, int lookbackDays,
                              long possibleNPlusOneQueryThreshold, long seqScanRowThreshold,
                              double minAvgDurationMs, double lowVarianceThreshold) {
        this.applicationName = applicationName;
        this.slowRequestThresholdMs = slowRequestThresholdMs;
        this.highErrorRateThreshold = highErrorRateThreshold;
        this.serverErrorStatusThreshold = serverErrorStatusThreshold;
        this.lookbackDays = lookbackDays;
        this.possibleNPlusOneQueryThreshold = possibleNPlusOneQueryThreshold;
        this.seqScanRowThreshold = seqScanRowThreshold;
        this.minAvgDurationMs = minAvgDurationMs;
        this.lowVarianceThreshold = lowVarianceThreshold;
    }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

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