package com.veloxdiag.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Real per-user account — the foundation of multi-tenancy. Before this, the
 * entire dashboard was single-shared-secret (see DashboardAccessFilter): one
 * token, everyone who had it saw every application's data. This entity plus
 * Application (see Application.java) is what makes "each user sees only
 * their own apps" possible.
 *
 * sessionToken is a deliberately simple approach for this scope: a single
 * opaque token stored directly on the row, replaced on every login, cleared
 * on logout. No expiry, no refresh tokens, no multi-device session list.
 * That's a real, documented limitation (see AuthController javadoc) — fine
 * for a small number of real users, not a JWT/OAuth-grade session system.
 */
@Entity
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    // Null when logged out. Regenerated (a new random value) on every
    // successful login — this means logging in on a new device silently
    // invalidates any previous session's token, which is a real, deliberate
    // simplification: "one active session at a time" rather than a session
    // list. Worth revisiting if real users ask for multi-device support.
    @Column(unique = true, length = 100)
    private String sessionToken;

    public User() {
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
}