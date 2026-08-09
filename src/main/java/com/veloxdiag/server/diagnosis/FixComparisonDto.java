package com.veloxdiag.server.diagnosis;

/**
 * What the dashboard's "Fixes" page actually renders — one row per marked
 * fix, before-side frozen at mark-time, after-side computed live from
 * telemetry that arrived since. improvementPercent is null while status
 * is WATCHING (not enough after-samples yet to say anything meaningful).
 */
public class FixComparisonDto {

    private Long id;
    private String applicationName;
    private String endpoint;
    private String ruleType;
    private String note;
    private String markedFixedAt;

    private Double beforeAvgDurationMs;
    private Double beforeMaxQueryCount;
    private Long beforeSampleCount;

    private Double afterAvgDurationMs;
    private Double afterMaxQueryCount;
    private Long afterSampleCount;

    private Double improvementPercent; // positive = faster, negative = slower; null if WATCHING
    private String status;
    private String verdictNote; // e.g. "still within this endpoint's normal noise range" — shown when
                                 // status stays WATCHING/NO_CHANGE for noise reasons rather than sample count

    public FixComparisonDto() {
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

    public String getMarkedFixedAt() { return markedFixedAt; }
    public void setMarkedFixedAt(String markedFixedAt) { this.markedFixedAt = markedFixedAt; }

    public Double getBeforeAvgDurationMs() { return beforeAvgDurationMs; }
    public void setBeforeAvgDurationMs(Double beforeAvgDurationMs) { this.beforeAvgDurationMs = beforeAvgDurationMs; }

    public Double getBeforeMaxQueryCount() { return beforeMaxQueryCount; }
    public void setBeforeMaxQueryCount(Double beforeMaxQueryCount) { this.beforeMaxQueryCount = beforeMaxQueryCount; }

    public Long getBeforeSampleCount() { return beforeSampleCount; }
    public void setBeforeSampleCount(Long beforeSampleCount) { this.beforeSampleCount = beforeSampleCount; }

    public Double getAfterAvgDurationMs() { return afterAvgDurationMs; }
    public void setAfterAvgDurationMs(Double afterAvgDurationMs) { this.afterAvgDurationMs = afterAvgDurationMs; }

    public Double getAfterMaxQueryCount() { return afterMaxQueryCount; }
    public void setAfterMaxQueryCount(Double afterMaxQueryCount) { this.afterMaxQueryCount = afterMaxQueryCount; }

    public Long getAfterSampleCount() { return afterSampleCount; }
    public void setAfterSampleCount(Long afterSampleCount) { this.afterSampleCount = afterSampleCount; }

    public Double getImprovementPercent() { return improvementPercent; }
    public void setImprovementPercent(Double improvementPercent) { this.improvementPercent = improvementPercent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVerdictNote() { return verdictNote; }
    public void setVerdictNote(String verdictNote) { this.verdictNote = verdictNote; }
}