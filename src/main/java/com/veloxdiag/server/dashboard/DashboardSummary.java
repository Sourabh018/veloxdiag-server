package com.veloxdiag.server.dashboard;

public class DashboardSummary {

    private long totalRequests;
    private double averageResponseTime;
    private long errorRequests;
    private long connectedApplications;

    // 0-100, computed from active diagnosis findings (see DashboardService.
    // computeHealthScore). 100 = no active findings; each HIGH/MEDIUM/LOW
    // finding subtracts a fixed weight, floored at 0.
    private int healthScore;

    public DashboardSummary() {
    }

    // Kept for any existing callers still constructing a summary without a
    // health score (e.g. tests) — defaults to 100 (no known problems) rather
    // than silently leaving it at 0, which would read as "critically unhealthy".
    public DashboardSummary(long totalRequests,
                            double averageResponseTime,
                            long errorRequests,
                            long connectedApplications) {
        this(totalRequests, averageResponseTime, errorRequests, connectedApplications, 100);
    }

    public DashboardSummary(long totalRequests,
                            double averageResponseTime,
                            long errorRequests,
                            long connectedApplications,
                            int healthScore) {
        this.totalRequests = totalRequests;
        this.averageResponseTime = averageResponseTime;
        this.errorRequests = errorRequests;
        this.connectedApplications = connectedApplications;
        this.healthScore = healthScore;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
    }

    public double getAverageResponseTime() {
        return averageResponseTime;
    }

    public void setAverageResponseTime(double averageResponseTime) {
        this.averageResponseTime = averageResponseTime;
    }

    public long getErrorRequests() {
        return errorRequests;
    }

    public void setErrorRequests(long errorRequests) {
        this.errorRequests = errorRequests;
    }

    public long getConnectedApplications() {
        return connectedApplications;
    }

    public void setConnectedApplications(long connectedApplications) {
        this.connectedApplications = connectedApplications;
    }

    public int getHealthScore() {
        return healthScore;
    }

    public void setHealthScore(int healthScore) {
        this.healthScore = healthScore;
    }
}