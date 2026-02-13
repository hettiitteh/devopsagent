package com.example.devopsagent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * Execution context passed to tools.
 * Contains session info, permissions, and environment data.
 */
@Data
@Builder
public class ToolContext {

    private String sessionId;
    private String agentId;
    private String toolProfile;
    private Set<String> allowedTools;
    private Map<String, String> environment;
    private boolean dryRun;
    private boolean approvalGranted;

    /**
     * Check if a specific tool is allowed in this context.
     */
    public boolean isToolAllowed(String toolName) {
        if (allowedTools == null) return false;
        return allowedTools.contains("*") || allowedTools.contains(toolName);
    }
}
