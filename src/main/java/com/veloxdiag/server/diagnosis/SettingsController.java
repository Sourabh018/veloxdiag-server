package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final DiagnosisService diagnosisService;

    public SettingsController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    @GetMapping
    public DiagnosisSettings getSettings() {
        return new DiagnosisSettings(
                diagnosisService.getSlowRequestThresholdMs(),
                diagnosisService.getHighErrorRateThreshold(),
                diagnosisService.getServerErrorStatusThreshold()
        );
    }

    @PutMapping
    public DiagnosisSettings updateSettings(@RequestBody DiagnosisSettings settings) {
        diagnosisService.setSlowRequestThresholdMs(settings.getSlowRequestThresholdMs());
        diagnosisService.setHighErrorRateThreshold(settings.getHighErrorRateThreshold());
        diagnosisService.setServerErrorStatusThreshold(settings.getServerErrorStatusThreshold());

        return getSettings();
    }
}