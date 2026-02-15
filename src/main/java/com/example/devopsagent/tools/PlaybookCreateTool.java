package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.service.PlaybookGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Playbook Create Tool - Allows the LLM agent to create new playbooks from chat.
 * The agent can reason about which tools to chain and in what order,
 * then save a reusable playbook definition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaybookCreateTool implements AgentTool {

    private final PlaybookGeneratorService playbookGeneratorService;

    @Override
    public String getName() { return "playbook_create"; }

    @Override
    public String getDescription() {
        return "Create a new reusable playbook (runbook) from a sequence of tool steps. " +
               "Use this when you want to save a remediation workflow as an automated playbook " +
               "that can be triggered later. Provide a name, description, and an ordered list of steps " +
               "where each step specifies a tool and its parameters.";
    }

    @Override
    public String getCategory() { return "remediation"; }

    @Override
    public boolean isMutating() { return true; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "name", Map.of("type", "string", "description",
                    "Name of the playbook (e.g., 'Docker Service Restart')"),
                "description", Map.of("type", "string", "description",
                    "Description of what this playbook does and when to use it"),
                "steps", Map.of("type", "array", "description",
                    "Ordered list of steps. Each step: {name, tool, parameters, on_failure}",
                    "items", Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "tool", Map.of("type", "string"),
                        "parameters", Map.of("type", "object"),
                        "on_failure", Map.of("type", "string", "enum", List.of("continue", "abort", "retry"))
                    ))),
                "tags", Map.of("type", "array", "description",
                    "Tags for categorization (e.g., ['docker', 'restart'])",
                    "items", Map.of("type", "string")),
                "approval_required", Map.of("type", "boolean", "description",
                    "Whether human approval is needed before execution", "default", false),
                "trigger_service", Map.of("type", "string", "description",
                    "Optional: service name to auto-trigger this playbook when it becomes unhealthy"),
                "trigger_severity", Map.of("type", "string", "description",
                    "Optional: severity level to auto-trigger (e.g., HIGH, CRITICAL)")
            ),
            "required", List.of("name", "steps")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String name = (String) parameters.get("name");
        String description = (String) parameters.getOrDefault("description", "Created by SRE Agent via chat");
        List<Map<String, Object>> stepsRaw = (List<Map<String, Object>>) parameters.get("steps");
        List<String> tags = parameters.containsKey("tags")
                ? (List<String>) parameters.get("tags") : List.of("agent-created");
        boolean approvalRequired = parameters.containsKey("approval_required")
                && Boolean.TRUE.equals(parameters.get("approval_required"));

        if (name == null || name.isBlank()) {
            return ToolResult.error("Playbook name is required.");
        }
        if (stepsRaw == null || stepsRaw.isEmpty()) {
            return ToolResult.error("At least one step is required.");
        }

        try {
            // Build steps
            List<Playbook.Step> steps = new ArrayList<>();
            for (int i = 0; i < stepsRaw.size(); i++) {
                Map<String, Object> raw = stepsRaw.get(i);
                String stepName = raw.containsKey("name") ? raw.get("name").toString() : "Step " + (i + 1);
                String tool = raw.containsKey("tool") ? raw.get("tool").toString() : null;
                if (tool == null || tool.isBlank()) {
                    return ToolResult.error("Step " + (i + 1) + " is missing a 'tool' field.");
                }
                Map<String, Object> stepParams = raw.containsKey("parameters")
                        ? (Map<String, Object>) raw.get("parameters") : Map.of();
                String onFailure = raw.containsKey("on_failure") ? raw.get("on_failure").toString() : "continue";

                steps.add(Playbook.Step.builder()
                        .order(i + 1)
                        .name(stepName)
                        .tool(tool)
                        .parameters(new HashMap<>(stepParams))
                        .onFailure(onFailure)
                        .build());
            }

            // Build triggers if service/severity specified
            List<Playbook.TriggerCondition> triggers = new ArrayList<>();
            String triggerService = (String) parameters.get("trigger_service");
            String triggerSeverity = (String) parameters.get("trigger_severity");
            if (triggerService != null || triggerSeverity != null) {
                triggers.add(Playbook.TriggerCondition.builder()
                        .type("service_unhealthy")
                        .service(triggerService != null ? triggerService : "*")
                        .severity(triggerSeverity != null ? triggerSeverity : "*")
                        .build());
            }

            Playbook playbook = Playbook.builder()
                    .name(name)
                    .description(description)
                    .approvalRequired(approvalRequired)
                    .maxExecutionTimeSeconds(300)
                    .tags(new ArrayList<>(tags))
                    .steps(steps)
                    .triggers(triggers)
                    .build();

            Playbook saved = playbookGeneratorService.savePlaybook(playbook,
                    Map.of("source", "agent-chat", "session_id", context.getSessionId() != null ? context.getSessionId() : ""));

            return ToolResult.text(String.format(
                    "Playbook '%s' created successfully!\n" +
                    "- ID: %s\n" +
                    "- Steps: %d\n" +
                    "- Approval required: %s\n" +
                    "- Tags: %s\n" +
                    "%s" +
                    "The playbook is now available in the Playbooks page and can be executed.",
                    saved.getName(), saved.getId(), saved.getSteps().size(),
                    saved.isApprovalRequired() ? "yes" : "no",
                    String.join(", ", saved.getTags()),
                    triggers.isEmpty() ? "" : "- Auto-trigger: " + triggerService + " (" + triggerSeverity + ")\n"
            ));

        } catch (Exception e) {
            log.error("Failed to create playbook via agent: {}", e.getMessage(), e);
            return ToolResult.error("Failed to create playbook: " + e.getMessage());
        }
    }
}
