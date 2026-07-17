package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/index-advisor")
public class IndexAdvisorController {

    private final IndexAdvisorService indexAdvisorService;

    public IndexAdvisorController(IndexAdvisorService indexAdvisorService) {
        this.indexAdvisorService = indexAdvisorService;
    }

    @GetMapping("/candidates")
    public List<IndexAdvisorFinding> getCandidates() {
        return indexAdvisorService.analyzeCandidates();
    }
}