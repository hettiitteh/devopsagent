package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.domain.AlertRule;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Alert Rule Management Tool - Create and manage alert rules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertManageTool implements AgentTool {

    private final AlertRuleRepository alertRuleRepository;

    @Override
    public String getName() { return "alert_manage"; }

    @Override
    public String getDescription() {
        return "Create, update, and manage alert rules for monitoring metrics. " +
               "Define thresholds, conditions, and severity levels for automated alerting. " +
               "Use to set up proactive monitoring and early warning systems.";
    }

    @Override
    public String getCategory() { return "alerting"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of("type", "string", "description",
                    "Action: create, update, delete, list, enable, disable"),
                "rule_id", Map.of("type", "string", "description", "Alert rule ID"),
                "name", Map.of("type", "string", "description", "Alert rule name"),
                "metric", Map.of("type", "string", "description", "Metric to monitor"),
                "condition", Map.of("type", "string", "description",
                    "Condition: GREATER_THAN, LESS_THAN, EQUALS, RATE_INCREASE, ABSENT"),
                "threshold", Map.of("type", "number", "description", "Threshold value"),
                "severity", Map.of("type", "string", "description", "Alert severity"),
                "service_name", Map.of("type", "string", "description", "Service to associate the rule with")
            ),
            "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String action = (String) parameters.get("action");

        return switch (action.toLowerCase()) {
            case "create" -> createRule(parameters);
            case "list" -> listRules();
            case "enable" -> toggleRule((String) parameters.get("rule_id"), true);
            case "disable" -> toggleRule((String) parameters.get("rule_id"), false);
            case "delete" -> deleteRule((String) parameters.get("rule_id"));
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    private ToolResult createRule(Map<String, Object> params) {
        AlertRule rule = AlertRule.builder()
                .name((String) params.getOrDefault("name", "Unnamed Alert"))
                .metric((String) params.getOrDefault("metric", ""))
                .condition(AlertRule.Condition.valueOf(
                        ((String) params.getOrDefault("condition", "GREATER_THAN")).toUpperCase()))
                .threshold(params.containsKey("threshold")
                        ? ((Number) params.get("threshold")).doubleValue() : 0.0)
                .severity(Incident.Severity.valueOf(
                        ((String) params.getOrDefault("severity", "MEDIUM")).toUpperCase()))
                .serviceName((String) params.getOrDefault("service_name", ""))
                .enabled(true)
                .build();

        rule = alertRuleRepository.save(rule);
        return ToolResult.text(String.format(
                "Alert Rule Created:\nID: %s\nName: %s\nMetric: %s\nCondition: %s %s\nSeverity: %s",
                rule.getId(), rule.getName(), rule.getMetric(),
                rule.getCondition(), rule.getThreshold(), rule.getSeverity()));
    }

    private ToolResult listRules() {
        List<AlertRule> rules = alertRuleRepository.findAll();
        if (rules.isEmpty()) {
            return ToolResult.text("No alert rules configured.");
        }
        StringBuilder sb = new StringBuilder("Alert Rules:\n");
        for (AlertRule rule : rules) {
            sb.append(String.format("- [%s] %s: %s %s %s (severity: %s, enabled: %s)\n",
                    rule.getId(), rule.getName(), rule.getMetric(),
                    rule.getCondition(), rule.getThreshold(),
                    rule.getSeverity(), rule.isEnabled()));
        }
        return ToolResult.text(sb.toString());
    }

    private ToolResult toggleRule(String ruleId, boolean enabled) {
        if (ruleId == null) return ToolResult.error("rule_id is required");
        return alertRuleRepository.findById(ruleId)
                .map(rule -> {
                    rule.setEnabled(enabled);
                    alertRuleRepository.save(rule);
                    return ToolResult.text("Alert rule " + ruleId + " " + (enabled ? "enabled" : "disabled") + ".");
                })
                .orElse(ToolResult.error("Alert rule not found: " + ruleId));
    }

    private ToolResult deleteRule(String ruleId) {
        if (ruleId == null) return ToolResult.error("rule_id is required");
        alertRuleRepository.deleteById(ruleId);
        return ToolResult.text("Alert rule " + ruleId + " deleted.");
    }
}
