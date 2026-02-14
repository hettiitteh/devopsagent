package com.example.devopsagent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.HashSet;
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

    /** Tools that have been explicitly approved by a human for this session */
    @Builder.Default
    private Set<String> approvedTools = new HashSet<>();

    /**
     * Check if a specific tool is allowed in this context.
     */
    public boolean isToolAllowed(String toolName) {
        if (allowedTools == null) return false;
        return allowedTools.contains("*") || allowedTools.contains(toolName);
    }

    /**
     * Check if a specific tool has been approved (either blanket or per-tool).
     */
    public boolean isToolApproved(String toolName) {
        return approvalGranted || (approvedTools != null && approvedTools.contains(toolName));
    }
}
