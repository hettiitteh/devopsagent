package com.example.devopsagent.controller;

import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.domain.ToolConfig;
import com.example.devopsagent.service.ToolConfigService;
import com.example.devopsagent.service.ToolConfigService.ToolConfigUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for managing tool configurations.
 * Replaces the old toggle-only approach with full CRUD + audit trail.
 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolConfigController {

    private final ToolConfigService toolConfigService;
    private final ToolRegistry toolRegistry;

    /**
     * List all tool configs merged with runtime metadata.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<ToolConfig> configs = toolConfigService.getAllConfigs();
        List<Map<String, Object>> result = configs.stream().map(config -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", config.getName());
            item.put("category", config.getCategory());
            item.put("description", config.getEffectiveDescription());
            item.put("defaultDescription", config.getDefaultDescription());
            item.put("customDescription", config.getDescription());
            item.put("enabled", config.isEnabled());
            item.put("approvalRequired", config.isApprovalRequired());
            item.put("mutating", config.isMutating());
            item.put("allowedProfiles", config.getAllowedProfiles());
            item.put("constraints", config.getConstraints());
            item.put("createdAt", config.getCreatedAt() != null ? config.getCreatedAt().toString() : null);
            item.put("updatedAt", config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : null);
            item.put("registered", toolRegistry.getTool(config.getName()).isPresent());
            item.put("executionCount", toolConfigService.getToolExecutionCount(config.getName()));
            Instant lastUsed = toolConfigService.getToolLastUsed(config.getName());
            item.put("lastUsed", lastUsed != null ? lastUsed.toString() : null);
            return item;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Get a single tool config with execution stats.
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getOne(@PathVariable String name) {
        ToolConfig config = toolConfigService.getConfig(name);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", config.getName());
        item.put("category", config.getCategory());
        item.put("description", config.getEffectiveDescription());
        item.put("defaultDescription", config.getDefaultDescription());
        item.put("customDescription", config.getDescription());
        item.put("enabled", config.isEnabled());
        item.put("approvalRequired", config.isApprovalRequired());
        item.put("mutating", config.isMutating());
        item.put("allowedProfiles", config.getAllowedProfiles());
        item.put("constraints", config.getConstraints());
        item.put("createdAt", config.getCreatedAt() != null ? config.getCreatedAt().toString() : null);
        item.put("updatedAt", config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : null);
        item.put("registered", toolRegistry.getTool(config.getName()).isPresent());
        item.put("executionCount", toolConfigService.getToolExecutionCount(config.getName()));
        Instant lastUsed = toolConfigService.getToolLastUsed(config.getName());
        item.put("lastUsed", lastUsed != null ? lastUsed.toString() : null);
        return ResponseEntity.ok(item);
    }

    /**
     * Update a tool configuration.
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String name,
            @RequestBody ToolConfigUpdateRequest request) {
        request.setName(name);
        ToolConfig updated = toolConfigService.updateConfig(name, request);
        return ResponseEntity.ok(Map.of(
                "status", "updated",
                "tool", updated.getName(),
                "enabled", updated.isEnabled(),
                "approvalRequired", updated.isApprovalRequired()
        ));
    }

    /**
     * Bulk update multiple tools.
     */
    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulkUpdate(
            @RequestBody List<ToolConfigUpdateRequest> requests) {
        List<ToolConfig> updated = toolConfigService.bulkUpdate(requests);
        return ResponseEntity.ok(Map.of(
                "status", "bulk_updated",
                "count", updated.size(),
                "tools", updated.stream().map(ToolConfig::getName).collect(Collectors.toList())
        ));
    }

    /**
     * Get execution audit trail for a specific tool.
     */
    @GetMapping("/{name}/executions")
    public ResponseEntity<Map<String, Object>> getExecutions(
            @PathVariable String name,
            @RequestParam(defaultValue = "50") int limit) {
        var history = toolConfigService.getToolExecutionHistory(name, limit);
        long totalCount = toolConfigService.getToolExecutionCount(name);
        return ResponseEntity.ok(Map.of(
                "tool", name,
                "totalExecutions", totalCount,
                "executions", history
        ));
    }
}
