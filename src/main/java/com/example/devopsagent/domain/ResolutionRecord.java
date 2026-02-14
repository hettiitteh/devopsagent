package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records which tool sequences successfully resolved incidents.
 * Used by the learning system to recommend approaches for similar future incidents.
 */
@Entity
@Table(name = "resolution_records", indexes = {
        @Index(name = "idx_resolution_service", columnList = "service"),
        @Index(name = "idx_resolution_success", columnList = "success")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolutionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "incident_id")
    private String incidentId;

    private String service;

    @Column(name = "incident_title")
    private String incidentTitle;

    /** JSON array of tool call sequence: [{"tool":"health_check","params":{...}}, ...] */
    @Column(name = "tool_sequence", length = 8192)
    private String toolSequence;

    /** Whether this resolution was successful */
    private boolean success;

    /** Time to resolve in milliseconds */
    @Column(name = "resolution_time_ms")
    private long resolutionTimeMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
