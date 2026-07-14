package com.veloxdiag.server.dashboard;

public class DashboardSummary {

    private long totalRequests;
    private double averageResponseTime;
    private long errorRequests;
    private long connectedApplications;

    public DashboardSummary() {
    }

    public DashboardSummary(long totalRequests,
                            double averageResponseTime,
                            long errorRequests,
                            long connectedApplications) {
        this.totalRequests = totalRequests;
        this.averageResponseTime = averageResponseTime;
        this.errorRequests = errorRequests;
        this.connectedApplications = connectedApplications;
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
}