package com.veloxdiag.server.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    private final AdminService adminService;

    // Injected from the ADMIN_RESET_TOKEN env var (see application.yaml) — never
    // shipped to the frontend build, never stored client-side. The person doing
    // the reset must type this token into the Settings page at the moment they
    // reset, so it only ever travels as a request header for that one call.
    @Value("${admin.reset-token:}")
    private String adminResetToken;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // No auth system exists on this project (deliberately deprioritized — see
    // PRD Section 5, no real multi-user need at current scale). This single
    // shared-secret header is the minimum viable guard against the dashboard's
    // public, unauthenticated Vercel deployment being used to wipe data by
    // anyone who simply finds the button — it is NOT a substitute for real auth
    // if this project ever grows beyond a single operator.
    @DeleteMapping("/api/admin/reset-application")
    public ResponseEntity<?> resetApplication(@RequestParam String applicationName,
                                               @RequestHeader(value = "X-Admin-Token", required = false) String providedToken) {
        if (adminResetToken == null || adminResetToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Reset is disabled: ADMIN_RESET_TOKEN is not configured on the server.");
        }
        if (providedToken == null || !adminResetToken.equals(providedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or missing admin token.");
        }
        if (applicationName == null || applicationName.isBlank()) {
            return ResponseEntity.badRequest().body("applicationName is required.");
        }

        AdminService.AdminResetResult result = adminService.resetApplication(applicationName);
        return ResponseEntity.ok(result);
    }
}