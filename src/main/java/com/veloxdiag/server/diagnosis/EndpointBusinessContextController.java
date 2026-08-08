package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Lets the app owner attach a plain-English "what this endpoint does for
 * the business/user" note to any endpoint, scoped per applicationName.
 * NarrativeService and RecommendationService read these (via the
 * repository directly) to ground AI explanations in real business impact
 * instead of only describing the technical pattern.
 *
 * Upsert semantics on PUT: one row per (applicationName, endpoint) —
 * calling PUT again for the same pair overwrites the existing description
 * rather than creating a duplicate.
 */
@RestController
@RequestMapping("/api/settings/business-context")
public class EndpointBusinessContextController {

    private final EndpointBusinessContextRepository repository;

    public EndpointBusinessContextController(EndpointBusinessContextRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<EndpointBusinessContext> listForApp(@RequestParam String applicationName) {
        return repository.findByApplicationName(applicationName);
    }

    @PutMapping
    public EndpointBusinessContext upsert(@RequestParam String applicationName,
                                           @RequestParam String endpoint,
                                           @RequestBody BusinessContextRequest body) {
        EndpointBusinessContext entity = repository
                .findByApplicationNameAndEndpoint(applicationName, endpoint)
                .orElseGet(() -> new EndpointBusinessContext(applicationName, endpoint, body.getDescription()));
        entity.setDescription(body.getDescription());
        return repository.save(entity);
    }

    @DeleteMapping
    public void delete(@RequestParam String applicationName, @RequestParam String endpoint) {
        repository.deleteByApplicationNameAndEndpoint(applicationName, endpoint);
    }

    public static class BusinessContextRequest {
        private String description;
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}