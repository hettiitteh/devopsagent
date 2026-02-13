package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a service being monitored by the SRE agent.
 */
@Entity
@Table(name = "monitored_services")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoredService {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceType type;

    @Column(name = "health_endpoint")
    private String healthEndpoint;

    @Column(name = "metrics_endpoint")
    private String metricsEndpoint;

    @Column(name = "log_source")
    private String logSource;

    private String namespace;

    private String cluster;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status")
    @Builder.Default
    private HealthStatus healthStatus = HealthStatus.UNKNOWN;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    @Column(name = "last_healthy_at")
    private Instant lastHealthyAt;

    @Column(name = "consecutive_failures")
    @Builder.Default
    private int consecutiveFailures = 0;

    @Column(name = "check_interval_seconds")
    @Builder.Default
    private int checkIntervalSeconds = 60;

    private boolean enabled;

    @Column(name = "metadata", length = 4096)
    private String metadata;

    public enum ServiceType {
        HTTP, GRPC, TCP, KUBERNETES_DEPLOYMENT, KUBERNETES_STATEFULSET,
        DOCKER_CONTAINER, PROCESS, DATABASE, MESSAGE_QUEUE, CUSTOM
    }

    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }
}
