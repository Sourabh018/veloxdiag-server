package com.veloxdiag.server.diagnosis;

import org.springframework.stereotype.Service;

/**
 * Shared, adjustable lookback window used by Diagnosis, Query Analyzer, and Index Advisor
 * so findings reflect recent app behavior instead of blending in stale historical data.
 */
@Service
public class TelemetryWindowSettings {

    private int lookbackDays = 7;

    public int getLookbackDays() {
        return lookbackDays;
    }

    public void setLookbackDays(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }
}