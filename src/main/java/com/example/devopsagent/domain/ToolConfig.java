package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persistent configuration for an agent tool.
 * Each row corresponds to a registered AgentTool, synced on startup.
 * User edits (enabled, approvalRequired, allowedProfiles, constraints) survive restarts.
 */
@Entity
@Table(name = "tool_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolConfig {

    /** Matches AgentTool.getName(), e.g. "health_check" */
    @Id
    private String name;

    /** Tool category, synced from Java class on boot */
    private String category;

    /** Editable description override. Null means use default from the Java class. */
    @Column(length = 2048)
    private String description;

    /** Default description from the Java class (not user-editable, refreshed on boot) */
    @Column(name = "default_description", length = 2048)
    private String defaultDescription;

    /** Whether this tool is enabled. Disabled tools are completely blocked. */
    @Builder.Default
    private boolean enabled = true;

    /** Whether this tool requires human approval before execution. */
    @Builder.Default
    private boolean approvalRequired = false;

    /** Whether this tool mutates state, synced from Java class */
    @Builder.Default
    private boolean mutating = false;

    /** Comma-separated list of allowed profiles, e.g. "sre,full". Null = all profiles. */
    @Column(name = "allowed_profiles", length = 2048)
    private String allowedProfiles;

    /** JSON constraints: max executions/hour, allowed params, blocked commands, etc. */
    @Column(length = 4096)
    private String constraints;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Returns the effective description (user override or default). */
    public String getEffectiveDescription() {
        return (description != null && !description.isBlank()) ? description : defaultDescription;
    }
}
