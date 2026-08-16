package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves AI-generated root-cause narratives, on-demand, for a single endpoint.
 * Separate from DiagnosisController (which serves findings for ALL endpoints
 * on every runDiagnosis() call) because narrative generation is a network
 * call and shouldn't run for endpoints nobody's looking at.
 *
 * Endpoint name comes in as a request param, not a path variable, because
 * normalized endpoints contain slashes (e.g. /api/exams/{id}) which would
 * break path-variable matching.
 */
@RestController
@RequestMapping("/api/diagnosis/narrative")
public class NarrativeController {

    private final DiagnosisService diagnosisService;
    private final NarrativeService narrativeService;

    public NarrativeController(DiagnosisService diagnosisService, NarrativeService narrativeService) {
        this.diagnosisService = diagnosisService;
        this.narrativeService = narrativeService;
    }

    @GetMapping
    public EndpointNarrative getNarrative(@org.springframework.web.bind.annotation.RequestParam String endpoint,
                                           @org.springframework.web.bind.annotation.RequestParam(required = false) String applicationName) {
        List<DiagnosisFinding> findings = diagnosisService.getFindingsForEndpoint(endpoint, applicationName);
        return narrativeService.generateNarrative(endpoint, findings, applicationName);
    }
}