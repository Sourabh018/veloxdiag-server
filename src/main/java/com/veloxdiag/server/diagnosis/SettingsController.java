package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final DiagnosisService diagnosisService;
    private final TelemetryWindowSettings windowSettings;

    public SettingsController(DiagnosisService diagnosisService, TelemetryWindowSettings windowSettings) {
        this.diagnosisService = diagnosisService;
        this.windowSettings = windowSettings;
    }

    @GetMapping
    public DiagnosisSettings getSettings() {
        return new DiagnosisSettings(
                diagnosisService.getSlowRequestThresholdMs(),
                diagnosisService.getHighErrorRateThreshold(),
                diagnosisService.getServerErrorStatusThreshold(),
                windowSettings.getLookbackDays()
        );
    }

    @PutMapping
    public DiagnosisSettings updateSettings(@RequestBody DiagnosisSettings settings) {
        diagnosisService.setSlowRequestThresholdMs(settings.getSlowRequestThresholdMs());
        diagnosisService.setHighErrorRateThreshold(settings.getHighErrorRateThreshold());
        diagnosisService.setServerErrorStatusThreshold(settings.getServerErrorStatusThreshold());
        windowSettings.setLookbackDays(settings.getLookbackDays());

        return getSettings();
    }
}