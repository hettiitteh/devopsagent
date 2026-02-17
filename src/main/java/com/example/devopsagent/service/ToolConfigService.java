package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.AuditLog;
import com.example.devopsagent.domain.ToolConfig;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.repository.AuditLogRepository;
import com.example.devopsagent.repository.ToolConfigRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages persistent tool configuration backed by the database.
 * Provides an in-memory cache for fast policy checks and syncs
 * tool metadata from Java classes on startup.
 */
@Slf4j
@Service
public class ToolConfigService {

    private final ToolConfigRepository toolConfigRepository;
    private final AuditLogRepository auditLogRepository;
    private final AgentProperties properties;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AuditService auditService;

    /** In-memory cache for fast lookups during tool execution. */
    private final Map<String, ToolConfig> cache = new ConcurrentHashMap<>();

    public ToolConfigService(ToolConfigRepository toolConfigRepository,
                             AuditLogRepository auditLogRepository,
                             AgentProperties properties,
                             @Lazy GatewayWebSocketHandler gatewayHandler,
                             @Lazy AuditService auditService) {
        this.toolConfigRepository = toolConfigRepository;
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
        this.gatewayHandler = gatewayHandler;
        this.auditService = auditService;
    }

    /**
     * Sync Java tool beans to the database on startup.
     * Creates new rows for newly discovered tools.
     * Refreshes category/mutating from the Java class but preserves user edits.
     * Marks removed tools as disabled.
     */
    public void syncTools(List<AgentTool> tools) {
        Set<String> currentToolNames = new HashSet<>();
        List<String> approvalRequiredFromYml = properties.getToolPolicy().getApprovalRequired();

        for (AgentTool tool : tools) {
            currentToolNames.add(tool.getName());
            Optional<ToolConfig> existing = toolConfigRepository.findById(tool.getName());

            if (existing.isPresent()) {
                ToolConfig config = existing.get();
                config.setCategory(tool.getCategory());
                config.setMutating(tool.isMutating());
                config.setDefaultDescription(tool.getDescription());
                toolConfigRepository.save(config);
                log.debug("Synced tool config: {} (existing)", tool.getName());
            } else {
                boolean needsApproval = approvalRequiredFromYml.contains(tool.getName())
                        || tool.requiresApproval();
                ToolConfig config = ToolConfig.builder()
                        .name(tool.getName())
                        .category(tool.getCategory())
                        .defaultDescription(tool.getDescription())
                        .enabled(true)
                        .approvalRequired(needsApproval)
                        .mutating(tool.isMutating())
                        .build();
                toolConfigRepository.save(config);
                log.info("Created new tool config: {} (approval={})", tool.getName(), needsApproval);
            }
        }

        // Mark removed tools as disabled
        List<ToolConfig> allConfigs = toolConfigRepository.findAll();
        for (ToolConfig config : allConfigs) {
            if (!currentToolNames.contains(config.getName()) && config.isEnabled()) {
                config.setEnabled(false);
                toolConfigRepository.save(config);
                log.warn("Tool '{}' no longer in codebase, marking disabled", config.getName());
            }
        }

        refreshCache();
        log.info("Tool config sync complete. {} tools configured.", cache.size());
    }

    /** Refresh the in-memory cache from the database. */
    public void refreshCache() {
        cache.clear();
        toolConfigRepository.findAll().forEach(tc -> cache.put(tc.getName(), tc));
    }

    /** Get config for a specific tool (cached). */
    public ToolConfig getConfig(String toolName) {
        return cache.get(toolName);
    }

    /** Get all tool configs (from cache). */
    public List<ToolConfig> getAllConfigs() {
        return cache.values().stream()
                .sorted(Comparator.comparing(ToolConfig::getCategory)
                        .thenComparing(ToolConfig::getName))
                .collect(Collectors.toList());
    }

    /** Update a tool config. Persists to DB, refreshes cache, audits the change. */
    public ToolConfig updateConfig(String toolName, ToolConfigUpdateRequest request) {
        ToolConfig config = toolConfigRepository.findById(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));

        if (request.getEnabled() != null) config.setEnabled(request.getEnabled());
        if (request.getApprovalRequired() != null) config.setApprovalRequired(request.getApprovalRequired());
        if (request.getDescription() != null) config.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        if (request.getAllowedProfiles() != null) config.setAllowedProfiles(request.getAllowedProfiles().isBlank() ? null : request.getAllowedProfiles());
        if (request.getConstraints() != null) config.setConstraints(request.getConstraints().isBlank() ? null : request.getConstraints());

        toolConfigRepository.save(config);
        cache.put(toolName, config);

        auditService.log("user", "CONFIG_CHANGED", toolName,
                Map.of("changes", request.toString()));

        gatewayHandler.broadcast("tool.config_changed", Map.of(
                "tool", toolName,
                "enabled", config.isEnabled(),
                "approvalRequired", config.isApprovalRequired()
        ));

        return config;
    }

    /** Bulk update multiple tools at once. */
    public List<ToolConfig> bulkUpdate(List<ToolConfigUpdateRequest> requests) {
        List<ToolConfig> updated = new ArrayList<>();
        for (ToolConfigUpdateRequest req : requests) {
            if (req.getName() != null) {
                try {
                    updated.add(updateConfig(req.getName(), req));
                } catch (Exception e) {
                    log.warn("Bulk update failed for tool {}: {}", req.getName(), e.getMessage());
                }
            }
        }
        return updated;
    }

    /** Fast check: is a tool enabled? */
    public boolean isToolEnabled(String toolName) {
        ToolConfig config = cache.get(toolName);
        return config == null || config.isEnabled();
    }

    /** Fast check: does a tool require approval? */
    public boolean isApprovalRequired(String toolName) {
        ToolConfig config = cache.get(toolName);
        return config != null && config.isApprovalRequired();
    }

    /** Check if a tool is allowed for a specific profile. */
    public boolean isToolAllowedForProfile(String toolName, String profile) {
        ToolConfig config = cache.get(toolName);
        if (config == null || config.getAllowedProfiles() == null || config.getAllowedProfiles().isBlank()) {
            return true; // null = allowed in all profiles
        }
        Set<String> allowed = Set.of(config.getAllowedProfiles().split(","));
        return allowed.contains(profile.trim());
    }

    /** Get execution history for a specific tool from the audit log. */
    public List<Map<String, Object>> getToolExecutionHistory(String toolName, int limit) {
        List<AuditLog> entries = auditLogRepository.findByActionAndTargetOrderByTimestampDesc(
                "TOOL_EXECUTED", toolName, PageRequest.of(0, limit));

        return entries.stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getId());
            entry.put("timestamp", e.getTimestamp().toString());
            entry.put("sessionId", e.getSessionId());
            entry.put("success", e.isSuccess());
            entry.put("details", e.getDetails());
            return entry;
        }).collect(Collectors.toList());
    }

    /** Get execution count for a tool. */
    public long getToolExecutionCount(String toolName) {
        return auditLogRepository.countByActionAndTarget("TOOL_EXECUTED", toolName);
    }

    /** Get the last execution timestamp for a tool. */
    public Instant getToolLastUsed(String toolName) {
        List<AuditLog> entries = auditLogRepository.findByActionAndTargetOrderByTimestampDesc(
                "TOOL_EXECUTED", toolName, PageRequest.of(0, 1));
        return entries.isEmpty() ? null : entries.get(0).getTimestamp();
    }

    /** DTO for update requests. */
    @Data
    public static class ToolConfigUpdateRequest {
        private String name;
        private Boolean enabled;
        private Boolean approvalRequired;
        private String description;
        private String allowedProfiles;
        private String constraints;
    }
}
