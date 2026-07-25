package com.veloxdiag.server.diagnosis;

import java.util.List;

public class Recommendation {

    private String endpoint;
    private String severity;              // rolled up from the driving finding
    private String ruleType;              // which finding this recommendation is for
    private String summary;               // one-line "what to do"
    private String detail;                // longer explanation, template-based by default
    private String suggestedCode;         // e.g. an example CREATE INDEX / JOIN FETCH snippet, may be null
    private boolean aiEnhanced;           // true if an LLM rewrote detail/suggestedCode, false if template-only
    private List<String> relatedFindings;

    public Recommendation(String endpoint, String severity, String ruleType, String summary, String detail,
                           String suggestedCode, boolean aiEnhanced, List<String> relatedFindings) {
        this.endpoint = endpoint;
        this.severity = severity;
        this.ruleType = ruleType;
        this.summary = summary;
        this.detail = detail;
        this.suggestedCode = suggestedCode;
        this.aiEnhanced = aiEnhanced;
        this.relatedFindings = relatedFindings;
    }

    public String getEndpoint() { return endpoint; }
    public String getSeverity() { return severity; }
    public String getRuleType() { return ruleType; }
    public String getSummary() { return summary; }
    public String getDetail() { return detail; }
    public String getSuggestedCode() { return suggestedCode; }
    public boolean isAiEnhanced() { return aiEnhanced; }
    public List<String> getRelatedFindings() { return relatedFindings; }
}