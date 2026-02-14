package com.example.devopsagent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for all available agent tools.
 * Like OpenClaw's 60+ built-in tools, tools are registered at startup
 * and can be dynamically added via the plugin system.
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    /**
     * Register a tool.
     */
    public void register(AgentTool tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {} ({})", tool.getName(), tool.getCategory());
    }

    /**
     * Unregister a tool.
     */
    public void unregister(String toolName) {
        tools.remove(toolName);
        log.info("Unregistered tool: {}", toolName);
    }

    /**
     * Get a tool by name.
     */
    public Optional<AgentTool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Get all registered tools.
     */
    public List<AgentTool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * Get tools filtered by the allowed set (from policy engine).
     */
    public List<AgentTool> getToolsForProfile(Set<String> allowedToolNames) {
        if (allowedToolNames.contains("*")) {
            return getAllTools();
        }
        return tools.values().stream()
                .filter(tool -> allowedToolNames.contains(tool.getName()))
                .collect(Collectors.toList());
    }

    /**
     * Get tools by category.
     */
    public List<AgentTool> getToolsByCategory(String category) {
        return tools.values().stream()
                .filter(tool -> tool.getCategory().equalsIgnoreCase(category))
                .collect(Collectors.toList());
    }

    /**
     * List all tool names and categories.
     */
    public Map<String, String> listTools() {
        Map<String, String> toolList = new LinkedHashMap<>();
        tools.values().stream()
                .sorted(Comparator.comparing(AgentTool::getCategory).thenComparing(AgentTool::getName))
                .forEach(tool -> toolList.put(tool.getName(), tool.getCategory() + " - " + tool.getDescription()));
        return toolList;
    }

    /**
     * List all tools with detailed info (name, category, description,
     * requiresApproval, isMutating).
     */
    public List<Map<String, Object>> listToolsDetailed(Set<String> approvalRequiredTools) {
        return tools.values().stream()
                .sorted(Comparator.comparing(AgentTool::getCategory).thenComparing(AgentTool::getName))
                .map(tool -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", tool.getName());
                    info.put("category", tool.getCategory());
                    info.put("description", tool.getDescription());
                    info.put("requiresApproval",
                            approvalRequiredTools != null && approvalRequiredTools.contains(tool.getName()));
                    info.put("isMutating", tool.isMutating());
                    return info;
                })
                .collect(Collectors.toList());
    }

    public int getToolCount() {
        return tools.size();
    }
}
