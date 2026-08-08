package com.veloxdiag.server.diagnosis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Stores a short, plain-English description of what an endpoint actually
 * DOES for the business/user — filled in once by the app owner via the
 * Settings page. This is the missing link between a technical finding
 * ("N+1 query, 47 extra SELECTs") and a business-relevant explanation
 * ("this is the page students check right before their exam — a slow
 * load here risks duplicate-click resubmissions during a high-stress
 * moment").
 *
 * Keyed by (applicationName, endpoint) where endpoint is the NORMALIZED
 * form (e.g. /api/exams/{id}/submit) so one row covers every instance of
 * that endpoint, not one row per exam ID. Optional — endpoints with no
 * row here simply get no business-context injection, narrative falls
 * back to describing the technical pattern only (today's behavior).
 */
@Entity
@Table(name = "endpoint_business_context",
        uniqueConstraints = @UniqueConstraint(columnNames = {"applicationName", "endpoint"}))
public class EndpointBusinessContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String applicationName;

    @Column(nullable = false)
    private String endpoint; // normalized form, e.g. /api/exams/{id}/submit

    @Column(length = 500, nullable = false)
    private String description; // plain English, owner-written, e.g.
    // "Student submits their final exam answers. This is the last step —
    //  a failure or long delay here directly risks a student losing their
    //  attempt or submitting twice."

    public EndpointBusinessContext() {
    }

    public EndpointBusinessContext(String applicationName, String endpoint, String description) {
        this.applicationName = applicationName;
        this.endpoint = endpoint;
        this.description = description;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}