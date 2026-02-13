package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.playbook.PlaybookEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Playbook Run Tool - Execute automated remediation playbooks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaybookRunTool implements AgentTool {

    private final PlaybookEngine playbookEngine;

    @Override
    public String getName() { return "playbook_run"; }

    @Override
    public String getDescription() {
        return "Execute a remediation playbook (runbook) to automatically fix known issues. " +
               "Playbooks contain a sequence of steps like health checks, service restarts, " +
               "log collection, and verification. Use when you identify an issue that matches a known pattern.";
    }

    @Override
    public String getCategory() { return "remediation"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public boolean isMutating() { return true; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of("type", "string", "description",
                    "Action: run, list, status, abort"),
                "playbook_id", Map.of("type", "string", "description",
                    "Playbook ID or name to execute"),
                "incident_id", Map.of("type", "string", "description",
                    "Associated incident ID"),
                "parameters", Map.of("type", "object", "description",
                    "Parameters to pass to the playbook"),
                "dry_run", Map.of("type", "boolean", "description",
                    "If true, show what would be executed without actually running", "default", false)
            ),
            "required", List.of("action")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String action = (String) parameters.get("action");

        return switch (action.toLowerCase()) {
            case "run" -> {
                String playbookId = (String) parameters.get("playbook_id");
                String incidentId = (String) parameters.getOrDefault("incident_id", null);
                Map<String, Object> playbookParams = (Map<String, Object>) parameters.getOrDefault("parameters", Map.of());
                boolean dryRun = parameters.containsKey("dry_run") && (boolean) parameters.get("dry_run");
                yield playbookEngine.executePlaybook(playbookId, incidentId, playbookParams, dryRun);
            }
            case "list" -> playbookEngine.listPlaybooks();
            case "status" -> {
                String executionId = (String) parameters.get("playbook_id");
                yield playbookEngine.getExecutionStatus(executionId);
            }
            case "abort" -> {
                String executionId = (String) parameters.get("playbook_id");
                yield playbookEngine.abortExecution(executionId);
            }
            default -> ToolResult.error("Unknown action: " + action);
        };
    }
}
