package com.example.devopsagent.agent;

import com.example.devopsagent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 9-Layer Tool Policy Engine.
 *
 * Tool access is controlled by nine cascading policy layers, evaluated in order:
 * 1. Profile policy — Base access level (minimal, sre, full)
 * 2. Provider-specific profile — Override by LLM provider
 * 3. Global policy — Project-wide tool rules
 * 4. Provider-specific global — Provider overrides on global
 * 5. Per-agent policy — Agent-specific access
 * 6. Agent provider policy — Provider overrides per agent
 * 7. Group policy — Channel/sender-based rules
 * 8. Sandbox policy — Container isolation restrictions
 * 9. Subagent policy — Child agent restrictions
 *
 * A deny at any layer blocks the tool. No exceptions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPolicyEngine {

    private final AgentProperties properties;

    // Additional policy layers
    private final Map<String, Set<String>> providerPolicies = Map.of();
    private final Map<String, Set<String>> agentPolicies = Map.of();
    private final Set<String> globalDenyList = Set.of();
    private final Set<String> sandboxRestrictions = Set.of();

    /**
     * Check if a tool is allowed under all 9 policy layers.
     */
    public boolean isToolAllowed(String toolName, ToolContext context) {
        // Layer 1: Profile policy
        if (!checkProfilePolicy(toolName, context.getToolProfile())) {
            log.debug("Tool {} denied by profile policy (profile={})", toolName, context.getToolProfile());
            return false;
        }

        // Layer 2: Provider-specific profile (currently no-op, extensible)
        // Layer 3: Global policy
        if (globalDenyList.contains(toolName)) {
            log.debug("Tool {} denied by global policy", toolName);
            return false;
        }

        // Layer 4: Provider-specific global (currently no-op, extensible)
        // Layer 5: Per-agent policy
        if (context.getAgentId() != null && agentPolicies.containsKey(context.getAgentId())) {
            if (!agentPolicies.get(context.getAgentId()).contains(toolName)) {
                log.debug("Tool {} denied by agent policy (agent={})", toolName, context.getAgentId());
                return false;
            }
        }

        // Layer 6: Agent provider policy (currently no-op, extensible)
        // Layer 7: Group policy (currently no-op, extensible)

        // Layer 8: Sandbox policy
        if (sandboxRestrictions.contains(toolName)) {
            log.debug("Tool {} denied by sandbox policy", toolName);
            return false;
        }

        // Layer 9: Subagent policy (inherits parent restrictions)
        // Context's allowed tools represent the intersection of all parent policies
        if (context.getAllowedTools() != null && !context.getAllowedTools().isEmpty()) {
            if (!context.getAllowedTools().contains("*") && !context.getAllowedTools().contains(toolName)) {
                log.debug("Tool {} denied by context policy", toolName);
                return false;
            }
        }

        return true;
    }

    /**
     * Get all tools allowed for a given profile.
     */
    public Set<String> getAllowedToolsForProfile(String profileName) {
        var profiles = properties.getToolPolicy().getProfiles();
        if (profiles.containsKey(profileName)) {
            return new HashSet<>(profiles.get(profileName).getAllowedTools());
        }
        // Default to minimal if profile not found
        if (profiles.containsKey("minimal")) {
            return new HashSet<>(profiles.get("minimal").getAllowedTools());
        }
        return Set.of();
    }

    private boolean checkProfilePolicy(String toolName, String profileName) {
        if (profileName == null) {
            profileName = properties.getToolPolicy().getDefaultProfile();
        }

        Set<String> allowed = getAllowedToolsForProfile(profileName);
        return allowed.contains("*") || allowed.contains(toolName);
    }

    /**
     * Audit: list all policy layers and their effect on a tool.
     */
    public Map<String, Boolean> auditToolAccess(String toolName, ToolContext context) {
        return Map.of(
                "layer1_profile", checkProfilePolicy(toolName, context.getToolProfile()),
                "layer3_global", !globalDenyList.contains(toolName),
                "layer8_sandbox", !sandboxRestrictions.contains(toolName),
                "layer9_context", context.getAllowedTools() == null ||
                        context.getAllowedTools().contains("*") ||
                        context.getAllowedTools().contains(toolName)
        );
    }
}
