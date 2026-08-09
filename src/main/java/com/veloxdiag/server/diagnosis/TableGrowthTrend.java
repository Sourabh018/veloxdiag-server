package com.veloxdiag.server.diagnosis;

import java.time.LocalDateTime;

/**
 * One table's growth trend, derived from comparing the EARLIEST and MOST
 * RECENT captured EXPLAIN plan for that table on a given endpoint. Answers
 * "why did this slowdown start NOW" — a query that was fine scanning 500
 * rows six months ago and is now scanning 8,000 rows didn't get a worse
 * query, the table just grew underneath it. This is the "WHEN/WHY NOW"
 * piece the original goal list asked for, distinct from WHY (root cause
 * pattern, e.g. N+1) which DiagnosisService already answers.
 */
public class TableGrowthTrend {

    private String tableName;
    private long earliestRowCount;
    private String earliestCapturedAt;
    private long latestRowCount;
    private String latestCapturedAt;
    private double growthPercent;
    private int dataPoints; // how many captures this trend is built from — low = less confident

    public TableGrowthTrend() {
    }

    public TableGrowthTrend(String tableName, long earliestRowCount, LocalDateTime earliestCapturedAt,
                             long latestRowCount, LocalDateTime latestCapturedAt, int dataPoints) {
        this.tableName = tableName;
        this.earliestRowCount = earliestRowCount;
        this.earliestCapturedAt = earliestCapturedAt.toString();
        this.latestRowCount = latestRowCount;
        this.latestCapturedAt = latestCapturedAt.toString();
        this.dataPoints = dataPoints;
        this.growthPercent = earliestRowCount == 0 ? 0.0
                : ((double) (latestRowCount - earliestRowCount) / earliestRowCount) * 100.0;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public long getEarliestRowCount() { return earliestRowCount; }
    public void setEarliestRowCount(long earliestRowCount) { this.earliestRowCount = earliestRowCount; }

    public String getEarliestCapturedAt() { return earliestCapturedAt; }
    public void setEarliestCapturedAt(String earliestCapturedAt) { this.earliestCapturedAt = earliestCapturedAt; }

    public long getLatestRowCount() { return latestRowCount; }
    public void setLatestRowCount(long latestRowCount) { this.latestRowCount = latestRowCount; }

    public String getLatestCapturedAt() { return latestCapturedAt; }
    public void setLatestCapturedAt(String latestCapturedAt) { this.latestCapturedAt = latestCapturedAt; }

    public double getGrowthPercent() { return growthPercent; }
    public void setGrowthPercent(double growthPercent) { this.growthPercent = growthPercent; }

    public int getDataPoints() { return dataPoints; }
    public void setDataPoints(int dataPoints) { this.dataPoints = dataPoints; }
}