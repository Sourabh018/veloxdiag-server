package com.veloxdiag.server.diagnosis;

import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final DiagnosisService diagnosisService;
    private final TelemetryWindowSettings windowSettings;
    private final AppSettingsRepository appSettingsRepository;

    public SettingsController(DiagnosisService diagnosisService,
                               TelemetryWindowSettings windowSettings,
                               AppSettingsRepository appSettingsRepository) {
        this.diagnosisService = diagnosisService;
        this.windowSettings = windowSettings;
        this.appSettingsRepository = appSettingsRepository;
    }

    /**
     * Runs once on startup, after Spring has constructed this bean (and therefore
     * after DiagnosisService/TelemetryWindowSettings already hold their hardcoded
     * defaults). If a persisted row exists, push it into those in-memory beans so
     * settings survive a restart/redeploy. If no row exists yet (first boot ever),
     * persist the current in-memory defaults so there's a baseline row going forward.
     */
    @PostConstruct
    public void loadPersistedSettings() {
        appSettingsRepository.findById(1L).ifPresentOrElse(
                saved -> {
                    diagnosisService.setSlowRequestThresholdMs(saved.getSlowRequestThresholdMs());
                    diagnosisService.setHighErrorRateThreshold(saved.getHighErrorRateThreshold());
                    diagnosisService.setServerErrorStatusThreshold(saved.getServerErrorStatusThreshold());
                    windowSettings.setLookbackDays(saved.getLookbackDays());
                },
                () -> appSettingsRepository.save(new AppSettingsEntity(
                        diagnosisService.getSlowRequestThresholdMs(),
                        diagnosisService.getHighErrorRateThreshold(),
                        diagnosisService.getServerErrorStatusThreshold(),
                        windowSettings.getLookbackDays()
                ))
        );
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
        // Update the live, in-memory beans — this is what the diagnosis engines
        // actually read from, so behavior changes immediately.
        diagnosisService.setSlowRequestThresholdMs(settings.getSlowRequestThresholdMs());
        diagnosisService.setHighErrorRateThreshold(settings.getHighErrorRateThreshold());
        diagnosisService.setServerErrorStatusThreshold(settings.getServerErrorStatusThreshold());
        windowSettings.setLookbackDays(settings.getLookbackDays());

        // Persist the same values so they survive the next restart/redeploy.
        appSettingsRepository.save(new AppSettingsEntity(
                settings.getSlowRequestThresholdMs(),
                settings.getHighErrorRateThreshold(),
                settings.getServerErrorStatusThreshold(),
                settings.getLookbackDays()
        ));

        return getSettings();
    }
}