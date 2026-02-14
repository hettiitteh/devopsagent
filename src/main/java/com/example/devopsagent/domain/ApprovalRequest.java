package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Tracks approval requests for tools that require human confirmation.
 */
@Entity
@Table(name = "approval_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "parameters", length = 4096)
    private String parameters;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "incident_id")
    private String incidentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "responded_by")
    private String respondedBy;

    @Column(length = 1024)
    private String reason;

    public enum ApprovalStatus {
        PENDING, APPROVED, DENIED, EXPIRED
    }

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) requestedAt = Instant.now();
    }
}
