package com.example.devopsagent.domain;

import com.example.devopsagent.playbook.Playbook;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.*;

/**
 * JPA entity for persisting playbook definitions in the database.
 * Replaces YAML file storage as the source of truth for playbooks.
 * Triggers, steps, variables, and tags are stored as JSON text columns.
 */
@Entity
@Table(name = "playbook_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookDefinition {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    private String version = "1.0";

    private String author;

    /** JSON serialization of List<TriggerCondition> */
    @Column(columnDefinition = "TEXT")
    private String triggersJson;

    /** JSON serialization of List<Step> */
    @Column(columnDefinition = "TEXT")
    private String stepsJson;

    /** JSON serialization of Map<String, Object> */
    @Column(columnDefinition = "TEXT")
    private String variablesJson;

    /** JSON serialization of List<String> */
    @Column(columnDefinition = "TEXT")
    private String tagsJson;

    @Builder.Default
    private boolean approvalRequired = false;

    @Builder.Default
    private int maxExecutionTimeSeconds = 300;

    @Builder.Default
    private boolean enabled = true;

    /** Origin: "yaml-import", "ui", "ai-suggestion", "chat" */
    @Builder.Default
    private String source = "ui";

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // ── Conversion helpers ──

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Convert this DB entity into the runtime Playbook POJO.
     */
    public Playbook toPlaybook() {
        Playbook.PlaybookBuilder pb = Playbook.builder()
                .id(id)
                .name(name)
                .description(description)
                .version(version)
                .author(author)
                .approvalRequired(approvalRequired)
                .maxExecutionTimeSeconds(maxExecutionTimeSeconds);

        try {
            if (triggersJson != null && !triggersJson.isBlank()) {
                pb.triggers(MAPPER.readValue(triggersJson,
                        new TypeReference<List<Playbook.TriggerCondition>>() {}));
            }
        } catch (Exception e) { /* leave default empty list */ }

        try {
            if (stepsJson != null && !stepsJson.isBlank()) {
                pb.steps(MAPPER.readValue(stepsJson,
                        new TypeReference<List<Playbook.Step>>() {}));
            }
        } catch (Exception e) { /* leave default empty list */ }

        try {
            if (variablesJson != null && !variablesJson.isBlank()) {
                pb.variables(MAPPER.readValue(variablesJson,
                        new TypeReference<Map<String, Object>>() {}));
            }
        } catch (Exception e) { /* leave default empty map */ }

        try {
            if (tagsJson != null && !tagsJson.isBlank()) {
                pb.tags(MAPPER.readValue(tagsJson,
                        new TypeReference<List<String>>() {}));
            }
        } catch (Exception e) { /* leave default empty list */ }

        return pb.build();
    }

    /**
     * Create a DB entity from a runtime Playbook POJO.
     */
    public static PlaybookDefinition fromPlaybook(Playbook playbook, String source) {
        PlaybookDefinitionBuilder def = PlaybookDefinition.builder()
                .id(playbook.getId() != null ? playbook.getId() : UUID.randomUUID().toString())
                .name(playbook.getName())
                .description(playbook.getDescription())
                .version(playbook.getVersion() != null ? playbook.getVersion() : "1.0")
                .author(playbook.getAuthor())
                .approvalRequired(playbook.isApprovalRequired())
                .maxExecutionTimeSeconds(playbook.getMaxExecutionTimeSeconds())
                .source(source != null ? source : "ui");

        try {
            if (playbook.getTriggers() != null) {
                def.triggersJson(MAPPER.writeValueAsString(playbook.getTriggers()));
            }
        } catch (Exception e) { /* leave null */ }

        try {
            if (playbook.getSteps() != null) {
                def.stepsJson(MAPPER.writeValueAsString(playbook.getSteps()));
            }
        } catch (Exception e) { /* leave null */ }

        try {
            if (playbook.getVariables() != null && !playbook.getVariables().isEmpty()) {
                def.variablesJson(MAPPER.writeValueAsString(playbook.getVariables()));
            }
        } catch (Exception e) { /* leave null */ }

        try {
            if (playbook.getTags() != null && !playbook.getTags().isEmpty()) {
                def.tagsJson(MAPPER.writeValueAsString(playbook.getTags()));
            }
        } catch (Exception e) { /* leave null */ }

        return def.build();
    }
}
