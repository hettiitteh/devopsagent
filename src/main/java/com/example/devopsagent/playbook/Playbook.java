package com.example.devopsagent.playbook;

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
    public static class TriggerCondition {
        private String type; // "incident_severity", "service_unhealthy", "alert_rule", "manual"
        private String service;
        private String severity;
        private String alertRuleId;
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
