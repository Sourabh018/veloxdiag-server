package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}