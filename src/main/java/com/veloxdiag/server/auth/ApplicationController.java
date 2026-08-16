package com.veloxdiag.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lets a logged-in user register a new application (generating its own
 * ingest key — see Application.java) and list the applications they own.
 * This is what the dashboard's app-selector dropdown now calls instead of
 * the old "distinct application names from all telemetry" query (see
 * DashboardService.getApplications() migration).
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationRepository applicationRepository;
    private final TelemetryRepository telemetryRepository;
    private final SlowQueryPlanRepository slowQueryPlanRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApplicationController(ApplicationRepository applicationRepository,
                                  TelemetryRepository telemetryRepository,
                                  SlowQueryPlanRepository slowQueryPlanRepository) {
        this.applicationRepository = applicationRepository;
        this.telemetryRepository = telemetryRepository;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
    }

    @PostMapping
    public ResponseEntity<?> registerApplication(@RequestBody RegisterRequest request) {
        User current = CurrentUserContext.get();
        if (current == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Login required.");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body("Application name is required.");
        }

        // If it already exists AND belongs to the caller, this is a retry —
        // e.g. the register call succeeded but the client never got past the
        // snippet screen (Render cold-start timeout, page reload, etc.) and
        // the dashboard's GET /api/applications came back empty on the next
        // load, sending them back through this same form. Returning the
        // existing row (not a fresh key — the old key is already in their
        // real app's config) is the useful behavior; a 409 with no way
        // forward is not.
        var existing = applicationRepository.findByName(request.getName());
        if (existing.isPresent()) {
            if (existing.get().getOwnerUserId().equals(current.getId())) {
                return ResponseEntity.ok(existing.get());
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An application with this name already exists.");
        }

        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        String ingestApiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Application app = new Application(request.getName(), current.getId(), ingestApiKey);
        applicationRepository.save(app);

        return ResponseEntity.ok(app);
    }

    @GetMapping
    public ResponseEntity<?> listMyApplications() {
        User current = CurrentUserContext.get();
        if (current == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Login required.");
        }
        List<AppSummary> apps = applicationRepository.findByOwnerUserId(current.getId()).stream()
                .map(a -> new AppSummary(a.getName(), a.getIngestApiKey()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(apps);
    }

    // Deletes the Application row AND all its data — every Telemetry and
    // SlowQueryPlan row for this applicationName goes with it. Same two
    // deleteByApplicationName queries the old Settings "Reset Application
    // Data" feature uses, just triggered from here instead. Irreversible:
    // no separate confirm token gate like Reset has, so the frontend is
    // responsible for a real confirmation step before calling this.
    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteApplication(@PathVariable String name) {
        User current = CurrentUserContext.get();
        if (current == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Login required.");
        }
        Application app = applicationRepository.findByName(name).orElse(null);
        if (app == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No such application.");
        }
        if (!app.getOwnerUserId().equals(current.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You do not own this application.");
        }
        telemetryRepository.deleteByApplicationName(name);
        slowQueryPlanRepository.deleteByApplicationName(name);
        applicationRepository.delete(app);
        return ResponseEntity.noContent().build();
    }

    // Same ownership check as deleteApplication, but keeps the Application
    // row (registration + ingest key) — only wipes this app's Telemetry and
    // SlowQueryPlan rows. This is the self-service reset every logged-in
    // owner can do on their own app; AdminController's reset-application
    // endpoint stays as the separate operator-only override (works even if
    // the caller isn't the owner, gated by ADMIN_RESET_TOKEN instead of
    // login) — the two are intentionally independent paths to the same
    // underlying AdminService.resetApplication.
    @DeleteMapping("/{name}/data")
    public ResponseEntity<?> resetApplicationData(@PathVariable String name) {
        User current = CurrentUserContext.get();
        if (current == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Login required.");
        }
        Application app = applicationRepository.findByName(name).orElse(null);
        if (app == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No such application.");
        }
        if (!app.getOwnerUserId().equals(current.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You do not own this application.");
        }
        int telemetryDeleted = telemetryRepository.deleteByApplicationName(name);
        int plansDeleted = slowQueryPlanRepository.deleteByApplicationName(name);
        return ResponseEntity.ok(new ResetResult(name, telemetryDeleted, plansDeleted));
    }

    public static class ResetResult {
        private String applicationName;
        private int telemetryRowsDeleted;
        private int slowQueryPlanRowsDeleted;
        public ResetResult(String applicationName, int telemetryRowsDeleted, int slowQueryPlanRowsDeleted) {
            this.applicationName = applicationName;
            this.telemetryRowsDeleted = telemetryRowsDeleted;
            this.slowQueryPlanRowsDeleted = slowQueryPlanRowsDeleted;
        }
        public String getApplicationName() { return applicationName; }
        public int getTelemetryRowsDeleted() { return telemetryRowsDeleted; }
        public int getSlowQueryPlanRowsDeleted() { return slowQueryPlanRowsDeleted; }
    }

    public static class RegisterRequest {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class AppSummary {
        private String name;
        private String ingestApiKey;
        public AppSummary(String name, String ingestApiKey) {
            this.name = name;
            this.ingestApiKey = ingestApiKey;
        }
        public String getName() { return name; }
        public String getIngestApiKey() { return ingestApiKey; }
    }
}