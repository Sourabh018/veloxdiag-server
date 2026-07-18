package com.veloxdiag.server.diagnosis.engine;

/**
 * A single condition within a RuleDefinition, e.g. "avgDurationMs GT 2000".
 * A RuleDefinition holds a list of these; ALL must pass (AND) for the rule to fire.
 *
 * Deliberately simple: four comparison operators, one metric, one threshold.
 * No OR/NOT/nesting — that's a conscious scope decision, not an oversight, so this
 * is finishable and testable rather than turning into a mini expression language.
 */
public class RuleCondition {

    private String metric;     // must match a key produced by EndpointMetrics, e.g. "avgDurationMs"
    private String operator;   // "GT", "GTE", "LT", "LTE"
    private double threshold;

    public RuleCondition() {
        // no-arg constructor required for JSON deserialization (Jackson)
    }

    public RuleCondition(String metric, String operator, double threshold) {
        this.metric = metric;
        this.operator = operator;
        this.threshold = threshold;
    }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    /**
     * Evaluates this condition against a computed metric value.
     * Returns false (does not throw) if the metric is missing, so a rule referencing
     * an unknown/misspelled metric simply never fires rather than crashing diagnosis
     * for every endpoint.
     */
    public boolean evaluate(Double metricValue) {
        if (metricValue == null || operator == null) {
            return false;
        }
        switch (operator) {
            case "GT":  return metricValue > threshold;
            case "GTE": return metricValue >= threshold;
            case "LT":  return metricValue < threshold;
            case "LTE": return metricValue <= threshold;
            default:    return false;
        }
    }
}