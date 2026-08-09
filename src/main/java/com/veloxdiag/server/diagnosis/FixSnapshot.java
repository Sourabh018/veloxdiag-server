package com.veloxdiag.server.diagnosis;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Created when the user clicks "Mark as Fixed" on a finding. Freezes the
 * endpoint's metrics AT THAT MOMENT (the "before" side of the comparison),
 * then FixTrackingService compares fresh telemetry arriving AFTER
 * markedFixedAt against this snapshot on demand — no background job, no
 * polling, computed live whenever the Fixes page is opened.
 *
 * This is the concrete implementation of two things the original spec
 * described but the codebase never had: a real "48s -> 2.4s" before/after
 * number (previously just an aspirational example in prose), and Regression
 * Watch (previously conflated with the unrelated PERFORMANCE_REGRESSION
 * baseline-split check) — REGRESSED status here specifically means "this
 * finding was marked fixed, and got worse again since," not "worse than its
 * own multi-day baseline."
 */
@Entity
@Table(name = "fix_snapshot")
public class FixSnapshot {

    public enum Status {
        WATCHING,        // fewer than MIN_AFTER_SAMPLES new requests since fix — not enough data yet
        IMPROVED,        // after-metrics genuinely better than before
        NO_CHANGE,       // after-metrics roughly the same as before
        REGRESSED        // after-metrics as bad or worse than before — the fix didn't hold
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String applicationName;
    private String endpoint;      // normalized form
    private String ruleType;      // which finding this fix addressed, e.g. POSSIBLE_N_PLUS_ONE
    private String note;          // optional free-text, e.g. "added JOIN FETCH to findExamAttempts"

    private LocalDateTime markedFixedAt;

    // Frozen at the moment of marking — the "before" side, never recalculated.
    private Double beforeAvgDurationMs;
    private Double beforeMaxQueryCount;
    private Long beforeSampleCount;

    // Standard deviation of duration at mark-time — the endpoint's natural
    // noise level BEFORE the fix. Added after a real false-positive was
    // caught in testing: a 45% "improvement" on /api/auth/register turned
    // out to be smaller than the endpoint's own stdDev (1690ms), i.e. well
    // within normal random swing for that endpoint, not a real change.
    // Nullable so existing rows created before this field don't break —
    // FixTrackingService falls back to the old (weaker) threshold-only
    // logic when this is null.
    private Double beforeStdDeviationMs;

    @Enumerated(EnumType.STRING)
    private Status status = Status.WATCHING;

    public FixSnapshot() {
    }

    public FixSnapshot(String applicationName, String endpoint, String ruleType, String note,
                        LocalDateTime markedFixedAt, Double beforeAvgDurationMs,
                        Double beforeMaxQueryCount, Long beforeSampleCount, Double beforeStdDeviationMs) {
        this.applicationName = applicationName;
        this.endpoint = endpoint;
        this.ruleType = ruleType;
        this.note = note;
        this.markedFixedAt = markedFixedAt;
        this.beforeAvgDurationMs = beforeAvgDurationMs;
        this.beforeMaxQueryCount = beforeMaxQueryCount;
        this.beforeSampleCount = beforeSampleCount;
        this.beforeStdDeviationMs = beforeStdDeviationMs;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getMarkedFixedAt() { return markedFixedAt; }
    public void setMarkedFixedAt(LocalDateTime markedFixedAt) { this.markedFixedAt = markedFixedAt; }

    public Double getBeforeAvgDurationMs() { return beforeAvgDurationMs; }
    public void setBeforeAvgDurationMs(Double beforeAvgDurationMs) { this.beforeAvgDurationMs = beforeAvgDurationMs; }

    public Double getBeforeMaxQueryCount() { return beforeMaxQueryCount; }
    public void setBeforeMaxQueryCount(Double beforeMaxQueryCount) { this.beforeMaxQueryCount = beforeMaxQueryCount; }

    public Long getBeforeSampleCount() { return beforeSampleCount; }
    public void setBeforeSampleCount(Long beforeSampleCount) { this.beforeSampleCount = beforeSampleCount; }

    public Double getBeforeStdDeviationMs() { return beforeStdDeviationMs; }
    public void setBeforeStdDeviationMs(Double beforeStdDeviationMs) { this.beforeStdDeviationMs = beforeStdDeviationMs; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}