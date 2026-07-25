package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// Path is /api/diagnosis/recommendations (not /api/recommendations) to match
// the dashboard's existing useRecommendations hook — confirmed against its
// actual apiClient.get() call rather than guessed.
@RestController
@RequestMapping("/api/diagnosis/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping
    public Map<String, List<Recommendation>> getRecommendations() {
        return recommendationService.getRecommendations();
    }

    // On-demand tailored suggestion, same pattern as /api/diagnosis/narrative:
    // not part of the default response above, called lazily when the user
    // clicks "Get AI Suggestion" on a specific finding's card. endpoint + ruleType
    // together identify which finding to explain, since one endpoint can have
    // several active findings at once.
    @GetMapping("/explain")
    public RecommendationExplanation explainRecommendation(@RequestParam String endpoint,
                                                             @RequestParam String ruleType) {
        return recommendationService.generateAiSuggestion(endpoint, ruleType);
    }
}