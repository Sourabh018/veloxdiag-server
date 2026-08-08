package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Powers the dashboard's "Fixes" page: mark a finding as addressed, then
 * watch real before/after numbers accumulate — the actual "48s -> 2.4s"
 * proof card the original spec described as an example, now backed by
 * real data instead of being an illustrative claim in prose.
 */
@RestController
@RequestMapping("/api/fixes")
public class FixTrackingController {

    private final FixTrackingService fixTrackingService;

    public FixTrackingController(FixTrackingService fixTrackingService) {
        this.fixTrackingService = fixTrackingService;
    }

    @PostMapping
    public FixSnapshot markAsFixed(@RequestParam String applicationName,
                                    @RequestParam String endpoint,
                                    @RequestParam String ruleType,
                                    @RequestParam(required = false) String note) {
        return fixTrackingService.markAsFixed(applicationName, endpoint, ruleType, note);
    }

    @GetMapping
    public List<FixComparisonDto> getComparisons(@RequestParam(required = false) String applicationName) {
        return fixTrackingService.getComparisons(applicationName);
    }
}