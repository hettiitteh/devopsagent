package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records the execution of a playbook/runbook.
 */
@Entity
@Table(name = "playbook_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "playbook_id", nullable = false)
    private String playbookId;

    @Column(name = "playbook_name")
    private String playbookName;

    @Column(name = "incident_id")
    private String incidentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "current_step")
    @Builder.Default
    private int currentStep = 0;

    @Column(name = "total_steps")
    @Builder.Default
    private int totalSteps = 0;

    @Column(name = "output", length = 16384)
    private String output;

    @Column(name = "error_message", length = 4096)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    public enum ExecutionStatus {
        PENDING, RUNNING, WAITING_APPROVAL, SUCCESS, FAILED, CANCELLED, TIMED_OUT
    }
}
