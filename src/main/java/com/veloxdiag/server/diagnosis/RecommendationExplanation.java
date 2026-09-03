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
    private boolean aiGenerated; // true only when real captured SQL/EXPLAIN evidence backed this suggestion —
                                  // false for a successful-but-generic AI write-up, a static template fallback,
                                  // or a discarded response that fabricated evidence it was never given.

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