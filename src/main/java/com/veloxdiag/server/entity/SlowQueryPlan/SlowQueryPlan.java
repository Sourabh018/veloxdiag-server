package com.veloxdiag.server.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores a captured EXPLAIN plan for one SQL statement from a slow request.
 * Only created for requests that already exceeded the slow-request threshold —
 * this is intentionally NOT captured for every query on every request, to avoid
 * adding EXPLAIN overhead to normal traffic. See veloxdiag-starter's
 * SlowQueryExplainCapture for where these get produced.
 *
 * sqlText is stored as captured (post-Hibernate, with '?' placeholders, not bind
 * values) — it's a plan for the query SHAPE, not a specific execution's exact
 * values, which is consistent with how EXPLAIN (without ANALYZE) works: it's the
 * planner's estimate, not a real execution trace.
 */
@Entity
@Table(name = "slow_query_plans")
public class SlowQueryPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String applicationName;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private Long requestDurationMs;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String explainPlan;

    // Cheap, honest signal extracted from explainPlan at capture time: does the
    // plan contain "Seq Scan"? Stored as its own column so the diagnosis rule can
    // query/filter on it directly instead of re-parsing explainPlan text every run.
    @Column(nullable = false)
    private boolean containsSeqScan;

    public SlowQueryPlan() {
        // required by JPA
    }

    public SlowQueryPlan(String applicationName, String endpoint, LocalDateTime timestamp,
                          Long requestDurationMs, String sqlText, String explainPlan, boolean containsSeqScan) {
        this.applicationName = applicationName;
        this.endpoint = endpoint;
        this.timestamp = timestamp;
        this.requestDurationMs = requestDurationMs;
        this.sqlText = sqlText;
        this.explainPlan = explainPlan;
        this.containsSeqScan = containsSeqScan;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public Long getRequestDurationMs() { return requestDurationMs; }
    public void setRequestDurationMs(Long requestDurationMs) { this.requestDurationMs = requestDurationMs; }

    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }

    public String getExplainPlan() { return explainPlan; }
    public void setExplainPlan(String explainPlan) { this.explainPlan = explainPlan; }

    public boolean isContainsSeqScan() { return containsSeqScan; }
    public void setContainsSeqScan(boolean containsSeqScan) { this.containsSeqScan = containsSeqScan; }
}