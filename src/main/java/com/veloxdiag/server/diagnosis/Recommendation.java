package com.veloxdiag.server.diagnosis;

import java.util.List;

/**
 * Field names deliberately match FindingCard's destructured props exactly
 * (ruleType, severity, endpoint, message, evidence, relatedFindings,
 * suggestedFix) so this DTO renders on the same shared card component as
 * DiagnosisFinding with zero frontend changes. "summary" (a short headline)
 * is folded into message rather than kept separate, since FindingCard only
 * ever renders one description field, not two.
 */
public class Recommendation {

    private String endpoint;
    private String severity;
    private String ruleType;
    private String message;
    private Object evidence;              // passthrough from the underlying DiagnosisFinding, may be null
    private String suggestedFix;           // e.g. an example CREATE INDEX / JOIN FETCH snippet, may be null
    private boolean aiEnhanced;            // true if an LLM generated suggestedFix, false if template-only
    private List<String> relatedFindings;

    public Recommendation(String endpoint, String severity, String ruleType, String summary, String detail,
                           Object evidence, String suggestedFix, boolean aiEnhanced, List<String> relatedFindings) {
        this.endpoint = endpoint;
        this.severity = severity;
        this.ruleType = ruleType;
        this.message = (detail == null || detail.isBlank()) ? summary : summary + " " + detail;
        this.evidence = evidence;
        this.suggestedFix = suggestedFix;
        this.aiEnhanced = aiEnhanced;
        this.relatedFindings = relatedFindings;
    }

    public String getEndpoint() { return endpoint; }
    public String getSeverity() { return severity; }
    public String getRuleType() { return ruleType; }
    public String getMessage() { return message; }
    public Object getEvidence() { return evidence; }
    public String getSuggestedFix() { return suggestedFix; }
    public boolean isAiEnhanced() { return aiEnhanced; }
    public List<String> getRelatedFindings() { return relatedFindings; }
}