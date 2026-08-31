package com.veloxdiag.server.diagnosis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    // Actually removes the row — distinct from the auto-reopen behavior,
    // which intentionally keeps a real fix's history when it regresses. This
    // is for undoing an accidental/wrong click, which nothing else in the
    // app (including the admin reset endpoints) could previously do.
    @DeleteMapping
    public ResponseEntity<?> deleteFix(@RequestParam String applicationName,
                                        @RequestParam String endpoint,
                                        @RequestParam String ruleType) {
        long deleted = fixTrackingService.deleteFixSnapshot(applicationName, endpoint, ruleType);
        if (deleted == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}