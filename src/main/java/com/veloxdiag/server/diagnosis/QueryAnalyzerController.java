package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/query-analyzer")
public class QueryAnalyzerController {

    private final QueryAnalyzerService queryAnalyzerService;

    public QueryAnalyzerController(QueryAnalyzerService queryAnalyzerService) {
        this.queryAnalyzerService = queryAnalyzerService;
    }

    @GetMapping("/trends")
    public List<EndpointTrend> getTrends() {
        return queryAnalyzerService.analyzeTrends();
    }
}