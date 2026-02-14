package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable audit trail entry. Records every significant action taken by
 * the agent, users, or the system (monitoring, escalation, playbooks).
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actor"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_target", columnList = "target"),
        @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Who performed the action: "agent", "user", "system", "monitoring", "playbook-engine" */
    @Column(nullable = false)
    private String actor;

    /** What happened: TOOL_EXECUTED, INCIDENT_CREATED, INCIDENT_RESOLVED, PLAYBOOK_RUN,
     *  PLAYBOOK_STEP, PLAYBOOK_COMPLETED, CONFIG_CHANGED, APPROVAL_REQUESTED,
     *  APPROVAL_GRANTED, APPROVAL_DENIED, ESCALATION, SERVICE_UNHEALTHY, SERVICE_RECOVERED */
    @Column(nullable = false)
    private String action;

    /** The target of the action (service name, incident ID, playbook ID, tool name, etc.) */
    private String target;

    /** JSON details about the action */
    @Column(length = 8192)
    private String details;

    /** Session ID if this action was part of an agent session */
    @Column(name = "session_id")
    private String sessionId;

    /** Whether the action succeeded */
    @Builder.Default
    private boolean success = true;

    @Column(nullable = false)
    private Instant timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) timestamp = Instant.now();
    }
}
