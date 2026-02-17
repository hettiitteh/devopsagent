package com.example.devopsagent.agent;

import java.util.Map;

/**
 * Interface for all agent tools.
 * Every tool follows the same contract:
 * - name: Tool identifier
 * - description: LLM guidance for when/how to use the tool
 * - parameters: JSON schema for tool parameters
 * - execute: Async execution returning a result
 */
public interface AgentTool {

    /**
     * Unique tool name (e.g., "health_check", "kubectl_exec").
     */
    String getName();

    /**
     * Human-readable description for the LLM.
     */
    String getDescription();

    /**
     * Category of this tool (monitoring, incident, playbook, infrastructure, etc.)
     */
    String getCategory();

    /**
     * JSON Schema describing the parameters this tool accepts.
     */
    Map<String, Object> getParameterSchema();

    /**
     * Execute the tool with the given parameters.
     *
     * @param parameters The tool parameters as a map
     * @param context    Execution context (session info, permissions, etc.)
     * @return Tool execution result
     */
    ToolResult execute(Map<String, Object> parameters, ToolContext context);

    /**
     * Whether this tool requires approval before execution.
     */
    default boolean requiresApproval() {
        return false;
    }

    /**
     * Whether this tool can modify state (used for policy checks).
     */
    default boolean isMutating() {
        return false;
    }
}
