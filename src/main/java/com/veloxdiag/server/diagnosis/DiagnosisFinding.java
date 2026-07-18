package com.veloxdiag.server.diagnosis;

import java.util.List;

public class DiagnosisFinding {

    private String ruleType;      // e.g. "SLOW_REQUEST", "HIGH_ERROR_RATE", "SERVER_ERROR", "ROOT_CAUSE_CORRELATION"
    private String severity;      // "LOW", "MEDIUM", "HIGH"
    private String endpoint;
    private String message;       // human-readable explanation
    private Object evidence;      // supporting data (avg duration, error count, etc.)

    // New fields, only populated by correlation findings (e.g. ROOT_CAUSE_CORRELATION).
    // Left null/empty for all existing individual findings — no behavior change for them.
    private String confidence;            // "HIGH", "MEDIUM", "LOW" — how strongly the evidence supports the correlation
    private List<String> relatedFindings; // ruleTypes this finding correlates, e.g. ["SLOW_REQUEST", "POSSIBLE_N_PLUS_ONE"]

    // Original constructor — unchanged signature, so every existing checkX() call site
    // in DiagnosisService still compiles with zero edits.
    public DiagnosisFinding(String ruleType, String severity, String endpoint, String message, Object evidence) {
        this(ruleType, severity, endpoint, message, evidence, null, List.of());
    }

    // New constructor for correlation findings that need confidence + relatedFindings.
    public DiagnosisFinding(String ruleType, String severity, String endpoint, String message, Object evidence,
                             String confidence, List<String> relatedFindings) {
        this.ruleType = ruleType;
        this.severity = severity;
        this.endpoint = endpoint;
        this.message = message;
        this.evidence = evidence;
        this.confidence = confidence;
        this.relatedFindings = relatedFindings;
    }

    public String getRuleType() { return ruleType; }
    public String getSeverity() { return severity; }
    public String getEndpoint() { return endpoint; }
    public String getMessage() { return message; }
    public Object getEvidence() { return evidence; }
    public String getConfidence() { return confidence; }
    public List<String> getRelatedFindings() { return relatedFindings; }
}