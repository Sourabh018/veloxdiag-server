package com.veloxdiag.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final SecureRandom secureRandom = new SecureRandom();

    public ApplicationController(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
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
        if (applicationRepository.findByName(request.getName()).isPresent()) {
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

    // Deletes only the Application row (registration + ingest key) — never
    // touches Telemetry, which stores applicationName as a plain string with
    // no FK back to this table (see Application.java javadoc on why name
    // isn't even per-owner-scoped). Re-registering the same name afterward
    // just picks the existing telemetry back up under a fresh key.
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
        applicationRepository.delete(app);
        return ResponseEntity.noContent().build();
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