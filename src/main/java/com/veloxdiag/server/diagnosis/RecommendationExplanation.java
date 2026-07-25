package com.veloxdiag.server.diagnosis;

/**
 * Response for the on-demand "Get AI Suggestion" action on a Recommendation
 * card. Mirrors EndpointNarrative's shape/purpose but for the fix side rather
 * than the root-cause side: generated lazily on click, not baked into the
 * default /api/diagnosis/recommendations response.
 */
public class RecommendationExplanation {

    private String endpoint;
    private String ruleType;
    private String suggestion;   // AI-generated, tailored to this endpoint's real evidence
    private boolean aiGenerated; // false when falling back to the static template (key exhausted, no evidence, etc.)

    public RecommendationExplanation(String endpoint, String ruleType, String suggestion, boolean aiGenerated) {
        this.endpoint = endpoint;
        this.ruleType = ruleType;
        this.suggestion = suggestion;
        this.aiGenerated = aiGenerated;
    }

    public String getEndpoint() { return endpoint; }
    public String getRuleType() { return ruleType; }
    public String getSuggestion() { return suggestion; }
    public boolean isAiGenerated() { return aiGenerated; }
}