package com.veloxdiag.server.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Phase 2.1 (JVM/GC monitoring) — server-side half only. Answers "is it slow
 * because of code, or because the JVM is thrashing on garbage collection."
 *
 * This entity/endpoint receives and stores the data — it does NOT capture
 * it. Capture (MemoryMXBean + GarbageCollectorMXBean) lives in
 * veloxdiag-starter, the client library running inside the monitored app,
 * a separate repo not covered here. The starter is expected to POST here
 * on a periodic timer (e.g. every 30-60s), not per-request like Telemetry —
 * JVM/GC stats change on their own timescale, not per HTTP call.
 *
 * heapUsedMb/heapMaxMb come straight from MemoryMXBean.getHeapMemoryUsage().
 * gcPauseMsSinceLastReport/gcCountSinceLastReport are deltas (not
 * cumulative totals) computed by the starter between reports, via
 * GarbageCollectorMXBean.getCollectionTime()/getCollectionCount() — so a
 * single row here represents "GC activity in the last reporting interval,"
 * directly comparable/summable across rows, rather than an ever-growing
 * cumulative counter the server would have to diff itself.
 */
@Entity
@Table(name = "jvm_metrics", indexes = {
        @Index(name = "idx_jvm_timestamp", columnList = "timestamp"),
        @Index(name = "idx_jvm_app_name", columnList = "applicationName")
})
public class JvmMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "applicationName is required")
    private String applicationName;

    @NotNull(message = "heapUsedMb is required")
    private Long heapUsedMb;

    @NotNull(message = "heapMaxMb is required")
    private Long heapMaxMb;

    // Milliseconds spent in GC pauses since the previous report — a delta,
    // not a cumulative total (see class javadoc). Nullable: an app with no
    // GC activity in the interval legitimately reports 0, but an older
    // starter version that predates GC tracking should send null rather
    // than a misleading 0.
    private Long gcPauseMsSinceLastReport;

    private Integer gcCountSinceLastReport;

    @NotNull(message = "timestamp is required")
    private LocalDateTime timestamp;

    public JvmMetrics() {
    }

    public JvmMetrics(String applicationName, Long heapUsedMb, Long heapMaxMb,
                       Long gcPauseMsSinceLastReport, Integer gcCountSinceLastReport,
                       LocalDateTime timestamp) {
        this.applicationName = applicationName;
        this.heapUsedMb = heapUsedMb;
        this.heapMaxMb = heapMaxMb;
        this.gcPauseMsSinceLastReport = gcPauseMsSinceLastReport;
        this.gcCountSinceLastReport = gcCountSinceLastReport;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public Long getHeapUsedMb() { return heapUsedMb; }
    public void setHeapUsedMb(Long heapUsedMb) { this.heapUsedMb = heapUsedMb; }

    public Long getHeapMaxMb() { return heapMaxMb; }
    public void setHeapMaxMb(Long heapMaxMb) { this.heapMaxMb = heapMaxMb; }

    public Long getGcPauseMsSinceLastReport() { return gcPauseMsSinceLastReport; }
    public void setGcPauseMsSinceLastReport(Long gcPauseMsSinceLastReport) { this.gcPauseMsSinceLastReport = gcPauseMsSinceLastReport; }

    public Integer getGcCountSinceLastReport() { return gcCountSinceLastReport; }
    public void setGcCountSinceLastReport(Integer gcCountSinceLastReport) { this.gcCountSinceLastReport = gcCountSinceLastReport; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}