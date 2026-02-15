package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a proactive risk assessment identified by LLM analysis.
 * Risks are emerging patterns or concerns detected before they become incidents.
 */
@Entity
@Table(name = "risk_assessments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String riskTitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Service name -- nullable because some risks are system-wide */
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    /** Category of risk, e.g. "flapping", "resource_exhaustion", "pattern_anomaly" */
    private String category;

    @Column(columnDefinition = "TEXT")
    private String suggestedAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.OPEN;

    @CreationTimestamp
    private Instant createdAt;

    private Instant acknowledgedAt;

    private String acknowledgedBy;

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum Status {
        OPEN, ACKNOWLEDGED, MITIGATED
    }
}
