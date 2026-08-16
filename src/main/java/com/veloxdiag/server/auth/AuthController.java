package com.veloxdiag.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Real per-user register/login/logout — replaces the single shared-secret
 * gate (DashboardAccessFilter) as the primary auth mechanism for anyone who
 * adopts multi-tenancy. DashboardAccessFilter is left in place, not deleted
 * — a deployment can still use it alone if it never wants multi-user
 * accounts at all; the two are independent, not layered on top of each
 * other.
 *
 * Known limitations of this MVP scope, documented rather than silently
 * hidden:
 * - No email verification — anyone can register with any email string.
 * - No password reset flow — a lost password currently means a manual DB fix.
 * - No rate limiting on login attempts.
 * These are real gaps for a production multi-user product; acceptable for
 * validating the core "isolated per-user dashboards" mechanism first.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // How long a session token stays valid after login/register. 24h is a
    // reasonable default for this scope — long enough not to be annoying,
    // short enough that a leaked token doesn't stay live indefinitely. Not
    // externalized to application.yml yet since nothing else about this
    // filter chain is configurable either; revisit if that changes.
    private static final long TOKEN_TTL_HOURS = 24;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()
                || request.getPassword() == null || request.getPassword().length() < 8) {
            return ResponseEntity.badRequest().body("Email is required and password must be at least 8 characters.");
        }
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An account with this email already exists.");
        }

        User user = new User(request.getEmail(), passwordEncoder.encode(request.getPassword()));
        user.setSessionToken(generateToken());
        user.setSessionTokenExpiresAt(java.time.LocalDateTime.now().plusHours(TOKEN_TTL_HOURS));
        userRepository.save(user);

        return ResponseEntity.ok(new AuthResponse(user.getSessionToken(), user.getEmail()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        Optional<User> found = userRepository.findByEmail(request.getEmail());
        if (found.isEmpty() || !passwordEncoder.matches(request.getPassword(), found.get().getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password.");
        }

        User user = found.get();
        // Regenerating on every login is deliberate — see User.sessionToken
        // javadoc: this is a real, documented "one active session" tradeoff.
        user.setSessionToken(generateToken());
        user.setSessionTokenExpiresAt(java.time.LocalDateTime.now().plusHours(TOKEN_TTL_HOURS));
        userRepository.save(user);

        return ResponseEntity.ok(new AuthResponse(user.getSessionToken(), user.getEmail()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        User current = CurrentUserContext.get();
        if (current != null) {
            current.setSessionToken(null);
            current.setSessionTokenExpiresAt(null);
            userRepository.save(current);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        User current = CurrentUserContext.get();
        if (current == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new AuthResponse(current.getSessionToken(), current.getEmail()));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static class AuthRequest {
        private String email;
        private String password;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class AuthResponse {
        private String token;
        private String email;
        public AuthResponse(String token, String email) {
            this.token = token;
            this.email = email;
        }
        public String getToken() { return token; }
        public String getEmail() { return email; }
    }
}