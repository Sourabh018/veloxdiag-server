package com.veloxdiag.server.entity;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "telemetry", indexes = {
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_app_name", columnList = "applicationName"),
        @Index(name = "idx_endpoint", columnList = "endpoint")
})
public class Telemetry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotBlank(message = "applicationName is required")
	private String applicationName;

	@NotBlank(message = "endpoint is required")
	private String endpoint;

	@NotBlank(message = "method is required")
	private String method;

	@NotNull(message = "status is required")
	private Integer status;

	@NotNull(message = "durationMs is required")
	private Long durationMs;

	@NotNull(message = "timestamp is required")
	private LocalDateTime timestamp;
	
	public Telemetry() {

	}
	
	public Telemetry(String applicationName,
            String endpoint,
            String method,
            Integer status,
            Long durationMs,
            LocalDateTime timestamp) {

this.applicationName = applicationName;
this.endpoint = endpoint;
this.method = method;
this.status = status;
this.durationMs = durationMs;
this.timestamp = timestamp;
}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
	}

	public LocalDateTime getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(LocalDateTime timestamp) {
		this.timestamp = timestamp;
	}

}