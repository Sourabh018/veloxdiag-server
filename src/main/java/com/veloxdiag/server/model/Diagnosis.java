package com.veloxdiag.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "diagnosis")
public class Diagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long telemetryId; // links back to the Telemetry record that triggered this

    private String applicationName;
    private String endpoint;
    private String method;

    @Enumerated(EnumType.STRING)
    private DiagnosisType type;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String message; // human-readable explanation

    private Long durationMs; // duration that triggered it, if relevant
    private Integer status;  // status code that triggered it, if relevant

    private LocalDateTime detectedAt;

    // --- Getters and setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTelemetryId() { return telemetryId; }
    public void setTelemetryId(Long telemetryId) { this.telemetryId = telemetryId; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public DiagnosisType getType() { return type; }
    public void setType(DiagnosisType type) { this.type = type; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }
}