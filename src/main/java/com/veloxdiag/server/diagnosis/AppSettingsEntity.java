package com.veloxdiag.server.diagnosis;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row persistence for DiagnosisSettings, so values survive server
 * restarts/redeploys instead of resetting to hardcoded defaults.
 *
 * Always read/written by fixed id = 1L — this table is never meant to hold
 * more than one row. DiagnosisService and TelemetryWindowSettings remain the
 * live, in-memory source of truth that the diagnosis engines read from; this
 * entity only exists to re-hydrate them on startup.
 */
@Entity
@Table(name = "app_settings")
public class AppSettingsEntity {

    @Id
    private Long id = 1L;

    private double slowRequestThresholdMs;
    private long highErrorRateThreshold;
    private int serverErrorStatusThreshold;
    private int lookbackDays;
    private long possibleNPlusOneQueryThreshold;

    public AppSettingsEntity() {
    }

    public AppSettingsEntity(double slowRequestThresholdMs, long highErrorRateThreshold,
                              int serverErrorStatusThreshold, int lookbackDays,
                              long possibleNPlusOneQueryThreshold) {
        this.id = 1L;
        this.slowRequestThresholdMs = slowRequestThresholdMs;
        this.highErrorRateThreshold = highErrorRateThreshold;
        this.serverErrorStatusThreshold = serverErrorStatusThreshold;
        this.lookbackDays = lookbackDays;
        this.possibleNPlusOneQueryThreshold = possibleNPlusOneQueryThreshold;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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