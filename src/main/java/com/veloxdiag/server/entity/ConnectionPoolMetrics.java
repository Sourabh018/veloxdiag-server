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
@Table(name = "connection_pool_metrics", indexes = {
        @Index(name = "idx_pool_timestamp", columnList = "timestamp"),
        @Index(name = "idx_pool_app_name", columnList = "applicationName")
})
public class ConnectionPoolMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "applicationName is required")
    private String applicationName;

    @NotNull(message = "activeConnections is required")
    private Integer activeConnections;

    @NotNull(message = "idleConnections is required")
    private Integer idleConnections;

    @NotNull(message = "totalConnections is required")
    private Integer totalConnections;

    @NotNull(message = "threadsAwaitingConnection is required")
    private Integer threadsAwaitingConnection;

    private Integer maxPoolSize;

    @NotNull(message = "timestamp is required")
    private LocalDateTime timestamp;

    public ConnectionPoolMetrics() {
    }

    public ConnectionPoolMetrics(String applicationName, Integer activeConnections,
                                  Integer idleConnections, Integer totalConnections,
                                  Integer threadsAwaitingConnection, Integer maxPoolSize,
                                  LocalDateTime timestamp) {
        this.applicationName = applicationName;
        this.activeConnections = activeConnections;
        this.idleConnections = idleConnections;
        this.totalConnections = totalConnections;
        this.threadsAwaitingConnection = threadsAwaitingConnection;
        this.maxPoolSize = maxPoolSize;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public Integer getActiveConnections() { return activeConnections; }
    public void setActiveConnections(Integer activeConnections) { this.activeConnections = activeConnections; }

    public Integer getIdleConnections() { return idleConnections; }
    public void setIdleConnections(Integer idleConnections) { this.idleConnections = idleConnections; }

    public Integer getTotalConnections() { return totalConnections; }
    public void setTotalConnections(Integer totalConnections) { this.totalConnections = totalConnections; }

    public Integer getThreadsAwaitingConnection() { return threadsAwaitingConnection; }
    public void setThreadsAwaitingConnection(Integer threadsAwaitingConnection) { this.threadsAwaitingConnection = threadsAwaitingConnection; }

    public Integer getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(Integer maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}