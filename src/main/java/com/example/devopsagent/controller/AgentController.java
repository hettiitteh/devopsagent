package com.example.devopsagent.controller;

import com.example.devopsagent.agent.AgentEngine;
import com.example.devopsagent.agent.SystemPromptBuilder;
import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.MonitoredService;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Agent REST API Controller.
 * Provides HTTP endpoints alongside the WebSocket gateway.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentEngine agentEngine;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;
    private final IncidentRepository incidentRepository;
    private final MonitoredServiceRepository serviceRepository;

    /**
     * Send a message to the agent and get a response.
     */
    @PostMapping("/chat")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.getOrDefault("session_id", UUID.randomUUID().toString());

        // Build context
        long activeIncidents = incidentRepository.findActiveIncidents().size();
        long unhealthyServices = serviceRepository.findByHealthStatus(MonitoredService.HealthStatus.UNHEALTHY).size();

        SystemPromptBuilder.AgentContext context = SystemPromptBuilder.AgentContext.builder()
                .activeIncidents((int) activeIncidents)
                .unhealthyServices((int) unhealthyServices)
                .build();

        return agentEngine.run(sessionId, message, context)
                .thenApply(response -> ResponseEntity.ok(Map.of(
                        "session_id", response.getSessionId(),
                        "response", response.getResponse(),
                        "tools_used", response.getToolsUsed(),
                        "iterations", response.getIterations()
                )));
    }

    /**
     * List all available tools (simple name → description map, kept for backward compat).
     */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, String>> listTools() {
        return ResponseEntity.ok(toolRegistry.listTools());
    }

    /**
     * List all tools with detailed info including approval status.
     */
    @GetMapping("/tools/detailed")
    public ResponseEntity<List<Map<String, Object>>> listToolsDetailed() {
        Set<String> approvalSet = new HashSet<>(properties.getToolPolicy().getApprovalRequired());
        return ResponseEntity.ok(toolRegistry.listToolsDetailed(approvalSet));
    }

    /**
     * Toggle approval requirement for a tool at runtime.
     */
    @PatchMapping("/tools/{toolName}/approval")
    public ResponseEntity<Map<String, Object>> toggleToolApproval(
            @PathVariable String toolName,
            @RequestBody Map<String, Object> body) {
        boolean requireApproval = Boolean.TRUE.equals(body.get("requiresApproval"));
        List<String> approvalList = properties.getToolPolicy().getApprovalRequired();

        if (requireApproval && !approvalList.contains(toolName)) {
            approvalList.add(toolName);
        } else if (!requireApproval) {
            approvalList.remove(toolName);
        }

        return ResponseEntity.ok(Map.of(
                "tool", toolName,
                "requiresApproval", approvalList.contains(toolName),
                "approvalRequiredTools", approvalList
        ));
    }

    /**
     * Get active agent sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> getSessions() {
        var sessions = agentEngine.getActiveSessions();
        return ResponseEntity.ok(Map.of(
                "count", sessions.size(),
                "sessions", sessions.keySet()
        ));
    }

    /**
     * Abort an agent session.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, String>> abortSession(@PathVariable String sessionId) {
        agentEngine.abort(sessionId);
        return ResponseEntity.ok(Map.of("status", "aborted", "session_id", sessionId));
    }
}
