package com.example.devopsagent.gateway;

import com.example.devopsagent.agent.AgentEngine;
import com.example.devopsagent.agent.SystemPromptBuilder;
import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.MonitoredService;
import com.example.devopsagent.memory.IncidentKnowledgeBase;
import com.example.devopsagent.monitoring.MonitoringService;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.plugin.PluginManager;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Registers all Gateway RPC methods at application startup.
 *
 * Like OpenClaw's 70+ RPC methods, organized by domain:
 * - gateway.* → Gateway status/control
 * - agent.* → Agent execution
 * - monitor.* → Monitoring operations
 * - incident.* → Incident management
 * - playbook.* → Playbook operations
 * - tool.* → Tool management
 * - plugin.* → Plugin management
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayRpcRegistration {

    private final GatewayRpcRouter router;
    private final AgentEngine agentEngine;
    private final ToolRegistry toolRegistry;
    private final MonitoringService monitoringService;
    private final IncidentRepository incidentRepository;
    private final MonitoredServiceRepository serviceRepository;
    private final PlaybookEngine playbookEngine;
    private final PluginManager pluginManager;
    private final IncidentKnowledgeBase knowledgeBase;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void registerRpcMethods() {
        log.info("Registering Gateway RPC methods...");

        // Gateway methods
        router.registerMethod("gateway.status", (params, session) -> Map.of(
                "version", "0.1.0",
                "sessions", Map.of("active", session != null ? 1 : 0),
                "tools", toolRegistry.getToolCount(),
                "timestamp", Instant.now().toString()
        ));

        router.registerMethod("gateway.methods", (params, session) -> router.listMethods());

        // Agent methods
        router.registerMethod("agent.run", (params, session) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) params;
            String message = (String) p.get("message");
            String sessionId = (String) p.getOrDefault("session_id", UUID.randomUUID().toString());

            var context = SystemPromptBuilder.AgentContext.builder()
                    .activeIncidents(incidentRepository.findActiveIncidents().size())
                    .build();

            agentEngine.run(sessionId, message, context);
            return Map.of("session_id", sessionId, "status", "started");
        });

        router.registerMethod("agent.sessions", (params, session) ->
                Map.of("sessions", agentEngine.getActiveSessions().keySet()));

        router.registerMethod("agent.abort", (params, session) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) params;
            String sessionId = (String) p.get("session_id");
            agentEngine.abort(sessionId);
            return Map.of("status", "aborted");
        });

        // Monitoring methods
        router.registerMethod("monitor.health", (params, session) ->
                monitoringService.getHealthSummary());

        router.registerMethod("monitor.services", (params, session) ->
                serviceRepository.findAll());

        // Incident methods
        router.registerMethod("incident.list", (params, session) ->
                incidentRepository.findActiveIncidents());

        router.registerMethod("incident.get", (params, session) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) params;
            String id = (String) p.get("id");
            return incidentRepository.findById(id).orElse(null);
        });

        router.registerMethod("incident.search", (params, session) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) params;
            String query = (String) p.getOrDefault("query", "");
            String service = (String) p.getOrDefault("service", null);
            return knowledgeBase.searchSimilarIncidents(query, service);
        });

        // Playbook methods
        router.registerMethod("playbook.list", (params, session) ->
                playbookEngine.listPlaybooks().getTextContent());

        router.registerMethod("playbook.run", (params, session) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) params;
            String playbookId = (String) p.get("playbook_id");
            String incidentId = (String) p.getOrDefault("incident_id", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> playbookParams = (Map<String, Object>) p.getOrDefault("parameters", Map.of());
            boolean dryRun = p.containsKey("dry_run") && (boolean) p.get("dry_run");
            return playbookEngine.executePlaybook(playbookId, incidentId, playbookParams, dryRun).getTextContent();
        });

        // Tool methods
        router.registerMethod("tool.list", (params, session) -> toolRegistry.listTools());

        // Plugin methods
        router.registerMethod("plugin.list", (params, session) -> pluginManager.listPlugins());

        log.info("Registered {} RPC methods", router.getMethodCount());
    }
}
