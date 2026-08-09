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