package com.veloxdiag.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * The missing piece that makes multi-tenancy real: before this,
 * "applicationName" was just a free-text string any telemetry payload could
 * claim (see TelemetryIngestFilter's old single-shared-secret design — one
 * token let a client post under ANY applicationName). This entity is the
 * source of truth for which applications exist, who owns each one, and
 * what key that specific application authenticates with.
 *
 * This also closes a previously-deferred gap ("per-app ingest key") for
 * free — it was deferred earlier as "not worth building until a second real
 * app connects," but multi-tenancy structurally requires it anyway (you
 * can't isolate users' data if every app shares one key), so it's built
 * here rather than as a separate pass.
 *
 * name is globally unique (not just per-owner) — deliberate: two different
 * users both calling their app "CET_CELL" would otherwise be ambiguous
 * everywhere applicationName is used as a plain string (dashboard URLs,
 * dropdown values, etc.), which is nearly the whole rest of the codebase.
 * Real product growth would eventually want per-owner namespacing instead;
 * documented as a known limitation, not solved here.
 */
@Entity
@Table(name = "application", uniqueConstraints = {
        @UniqueConstraint(columnNames = "name"),
        @UniqueConstraint(columnNames = "ingestApiKey")
})
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 64)
    private String ingestApiKey;

    public Application() {
    }

    public Application(String name, Long ownerUserId, String ingestApiKey) {
        this.name = name;
        this.ownerUserId = ownerUserId;
        this.ingestApiKey = ingestApiKey;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getIngestApiKey() { return ingestApiKey; }
    public void setIngestApiKey(String ingestApiKey) { this.ingestApiKey = ingestApiKey; }
}