package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Defines alerting rules for metric thresholds and conditions.
 */
@Entity
@Table(name = "alert_rules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(length = 2048)
    private String description;

    @Column(nullable = false)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Condition condition;

    @Column(nullable = false)
    private double threshold;

    @Column(name = "duration_seconds")
    @Builder.Default
    private int durationSeconds = 60;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Incident.Severity severity;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "playbook_id")
    private String playbookId;

    private boolean enabled;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "trigger_count")
    @Builder.Default
    private int triggerCount = 0;

    @Column(name = "cooldown_minutes")
    @Builder.Default
    private int cooldownMinutes = 5;

    public enum Condition {
        GREATER_THAN, LESS_THAN, EQUALS, NOT_EQUALS,
        GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL,
        RATE_INCREASE, RATE_DECREASE, ABSENT
    }
}
