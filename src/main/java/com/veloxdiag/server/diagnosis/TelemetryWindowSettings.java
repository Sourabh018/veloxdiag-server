package com.veloxdiag.server.diagnosis;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Per-application adjustable lookback window used by Diagnosis, Query Analyzer,
 * Index Advisor, and Dashboard, so findings reflect recent behavior for the
 * specific application being viewed, instead of one shared global window.
 *
 * Each application (CET_CELL, AgroMart, etc.) can have its own lookback value.
 * A DEFAULT_KEY bucket holds the value used for "All Apps" (null/blank
 * applicationName) and as the seed value for any app that hasn't saved its
 * own settings yet.
 */
@Service
public class TelemetryWindowSettings {

    static final String DEFAULT_KEY = "__default__";
    private static final int DEFAULT_LOOKBACK_DAYS = 7;

    private final Map<String, Integer> lookbackDaysByApp = new ConcurrentHashMap<>();

    public TelemetryWindowSettings() {
        lookbackDaysByApp.put(DEFAULT_KEY, DEFAULT_LOOKBACK_DAYS);
    }

    private static String resolveKey(String applicationName) {
        return (applicationName == null || applicationName.isBlank()) ? DEFAULT_KEY : applicationName;
    }

    public int getLookbackDays(String applicationName) {
        return lookbackDaysByApp.getOrDefault(resolveKey(applicationName), DEFAULT_LOOKBACK_DAYS);
    }

    public void setLookbackDays(String applicationName, int lookbackDays) {
        lookbackDaysByApp.put(resolveKey(applicationName), lookbackDays);
    }

    // No-arg overloads operate on the DEFAULT_KEY bucket — kept for any
    // caller that hasn't been updated to pass an applicationName yet.
    public int getLookbackDays() {
        return getLookbackDays(null);
    }

    public void setLookbackDays(int lookbackDays) {
        setLookbackDays(null, lookbackDays);
    }
}