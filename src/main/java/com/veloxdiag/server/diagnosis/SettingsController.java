package com.veloxdiag.server.diagnosis;

import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.*;

/**
 * Settings are scoped per application (applicationName query param on both
 * endpoints) instead of one global row shared by every monitored app.
 *
 * PUT now pushes the saved values into DiagnosisService, TelemetryWindowSettings,
 * and IndexAdvisorService's per-app in-memory maps immediately (via their
 * appName-aware setters), so evaluation behavior changes right away — not just
 * the persisted row and the Settings page display. On startup, every persisted
 * app row is loaded back into those same per-app maps, so tuned thresholds
 * survive a restart/redeploy for every app, not just one fixed global row.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final DiagnosisService diagnosisService;
    private final TelemetryWindowSettings windowSettings;
    private final IndexAdvisorService indexAdvisorService;
    private final AppSettingsRepository appSettingsRepository;

    public SettingsController(DiagnosisService diagnosisService,
                               TelemetryWindowSettings windowSettings,
                               IndexAdvisorService indexAdvisorService,
                               AppSettingsRepository appSettingsRepository) {
        this.diagnosisService = diagnosisService;
        this.windowSettings = windowSettings;
        this.indexAdvisorService = indexAdvisorService;
        this.appSettingsRepository = appSettingsRepository;
    }

    /**
     * Runs once on startup. Loads every persisted app_settings row into the
     * per-app in-memory maps of DiagnosisService/TelemetryWindowSettings/
     * IndexAdvisorService, so each application's tuned thresholds are active
     * immediately, not just the first one queried. Apps with no row yet keep
     * running on the DEFAULT_KEY bucket's hardcoded defaults until their first
     * GET (which lazily creates + saves a default row for them).
     */
    @PostConstruct
    public void loadPersistedSettings() {
        for (AppSettingsEntity saved : appSettingsRepository.findAll()) {
            applyToLiveBeans(saved);
        }
    }

    private void applyToLiveBeans(AppSettingsEntity saved) {
        String appName = saved.getApplicationName();
        diagnosisService.setThresholds(
                appName,
                saved.getSlowRequestThresholdMs(),
                saved.getHighErrorRateThreshold(),
                saved.getServerErrorStatusThreshold(),
                saved.getPossibleNPlusOneQueryThreshold(),
                saved.getSeqScanRowThreshold()
        );
        windowSettings.setLookbackDays(appName, saved.getLookbackDays());
        indexAdvisorService.setMinAvgDurationMs(appName, saved.getMinAvgDurationMs());
        indexAdvisorService.setLowVarianceThreshold(appName, saved.getLowVarianceThreshold());
    }

    /**
     * Builds a default settings row for an app with no persisted row yet,
     * seeded from the current DEFAULT_KEY bean values (same defaults every
     * app effectively started with before per-app scoping existed).
     */
    private AppSettingsEntity buildDefaultFor(String applicationName) {
        return new AppSettingsEntity(
                applicationName,
                diagnosisService.getSlowRequestThresholdMs(),
                diagnosisService.getHighErrorRateThreshold(),
                diagnosisService.getServerErrorStatusThreshold(),
                windowSettings.getLookbackDays(),
                diagnosisService.getPossibleNPlusOneQueryThreshold(),
                diagnosisService.getSeqScanRowThreshold(),
                indexAdvisorService.getMinAvgDurationMs(),
                indexAdvisorService.getLowVarianceThreshold()
        );
    }

    private DiagnosisSettings toDto(AppSettingsEntity entity) {
        return new DiagnosisSettings(
                entity.getApplicationName(),
                entity.getSlowRequestThresholdMs(),
                entity.getHighErrorRateThreshold(),
                entity.getServerErrorStatusThreshold(),
                entity.getLookbackDays(),
                entity.getPossibleNPlusOneQueryThreshold(),
                entity.getSeqScanRowThreshold(),
                entity.getMinAvgDurationMs(),
                entity.getLowVarianceThreshold()
        );
    }

    @GetMapping
    public DiagnosisSettings getSettings(@RequestParam String applicationName) {
        AppSettingsEntity entity = appSettingsRepository.findById(applicationName)
                .orElseGet(() -> {
                    AppSettingsEntity created = appSettingsRepository.save(buildDefaultFor(applicationName));
                    applyToLiveBeans(created); // seed the live maps too, not just the DB row
                    return created;
                });
        return toDto(entity);
    }

    @PutMapping
    public DiagnosisSettings updateSettings(@RequestParam String applicationName,
                                             @RequestBody DiagnosisSettings settings) {
        AppSettingsEntity entity = new AppSettingsEntity(
                applicationName,
                settings.getSlowRequestThresholdMs(),
                settings.getHighErrorRateThreshold(),
                settings.getServerErrorStatusThreshold(),
                settings.getLookbackDays(),
                settings.getPossibleNPlusOneQueryThreshold(),
                settings.getSeqScanRowThreshold(),
                settings.getMinAvgDurationMs(),
                settings.getLowVarianceThreshold()
        );
        appSettingsRepository.save(entity);
        applyToLiveBeans(entity); // evaluation reflects the change immediately, no restart needed

        return toDto(entity);
    }
}