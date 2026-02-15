package com.example.devopsagent.playbook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * YAML-based Playbook definition.
 * A playbook is a sequence of steps for automated remediation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Playbook {

    private String id;
    private String name;
    private String description;
    private String version;
    private String author;

    /**
     * Trigger conditions for auto-execution.
     */
    @Builder.Default
    private List<TriggerCondition> triggers = new ArrayList<>();

    /**
     * Ordered list of steps to execute.
     */
    @Builder.Default
    private List<Step> steps = new ArrayList<>();

    /**
     * Variables that can be parameterized.
     */
    @Builder.Default
    private Map<String, Object> variables = Map.of();

    /**
     * Tags for categorization.
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private boolean approvalRequired;
    private int maxExecutionTimeSeconds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TriggerCondition {
        private String type; // "incident_severity", "service_unhealthy", "alert_rule", "manual"
        private String service;

        /**
         * Multiple severities this trigger responds to.
         * Example: ["HIGH", "CRITICAL"] or ["*"] for any.
         */
        @Builder.Default
        private List<String> severities = new ArrayList<>();

        private String alertRuleId;

        /**
         * Backward-compatible setter: if old YAML/JSON has a single "severity" string,
         * convert it to a one-element severities list.
         */
        @JsonSetter("severity")
        public void setSeverityCompat(String severity) {
            if (severity != null && !severity.isBlank()) {
                if (this.severities == null) this.severities = new ArrayList<>();
                if (!this.severities.contains(severity)) {
                    this.severities.add(severity);
                }
            }
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {
        private int order;
        private String name;
        private String description;
        private String tool;
        private Map<String, Object> parameters;
        private String onFailure; // "continue", "abort", "retry"
        private int maxRetries;
        private int retryDelaySeconds;
        private int timeoutSeconds;
        private String condition; // SpEL condition for conditional execution
    }
}
