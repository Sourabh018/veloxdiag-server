package com.veloxdiag.server.diagnosis;

public class DiagnosisFinding {

    private String ruleType;      // e.g. "SLOW_REQUEST", "HIGH_ERROR_RATE", "SERVER_ERROR"
    private String severity;      // "LOW", "MEDIUM", "HIGH"
    private String endpoint;
    private String message;       // human-readable explanation
    private Object evidence;      // supporting data (avg duration, error count, etc.)

    public DiagnosisFinding(String ruleType, String severity, String endpoint, String message, Object evidence) {
        this.ruleType = ruleType;
        this.severity = severity;
        this.endpoint = endpoint;
        this.message = message;
        this.evidence = evidence;
    }

    public String getRuleType() { return ruleType; }
    public String getSeverity() { return severity; }
    public String getEndpoint() { return endpoint; }
    public String getMessage() { return message; }
    public Object getEvidence() { return evidence; }
}