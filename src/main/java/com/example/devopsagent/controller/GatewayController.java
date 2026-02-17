package com.example.devopsagent.controller;

import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.gateway.GatewayRpcRouter;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.monitoring.MonitoringService;
import com.example.devopsagent.plugin.PluginManager;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Gateway Status and Dashboard Controller.
 * Provides system-wide status via the gateway.status RPC method.
 */
@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayWebSocketHandler gatewayHandler;
    private final GatewayRpcRouter rpcRouter;
    private final ToolRegistry toolRegistry;
    private final PluginManager pluginManager;
    private final MonitoringService monitoringService;
    private final IncidentRepository incidentRepository;
    private final MonitoredServiceRepository serviceRepository;

    /**
     * Get full gateway status — system overview.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "version", "0.1.0",
                "architecture", "Jarvis SRE",
                "uptime", getUptime(),
                "gateway", Map.of(
                        "active_sessions", gatewayHandler.getActiveSessionCount(),
                        "rpc_methods", rpcRouter.getMethodCount()
                ),
                "agent", Map.of(
                        "tools_registered", toolRegistry.getToolCount(),
                        "tools", toolRegistry.listTools()
                ),
                "monitoring", monitoringService.getHealthSummary(),
                "incidents", Map.of(
                        "active", incidentRepository.findActiveIncidents().size(),
                        "total", incidentRepository.count()
                ),
                "plugins", Map.of(
                        "active", pluginManager.getActivePlugins().size(),
                        "list", pluginManager.listPlugins()
                ),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * List all registered RPC methods.
     */
    @GetMapping("/methods")
    public ResponseEntity<Map<String, String>> listMethods() {
        return ResponseEntity.ok(rpcRouter.listMethods());
    }

    /**
     * Get active WebSocket sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> getSessions() {
        var sessions = gatewayHandler.getActiveSessions();
        return ResponseEntity.ok(Map.of(
                "count", sessions.size(),
                "sessions", sessions.entrySet().stream()
                        .map(e -> Map.of(
                                "id", e.getKey(),
                                "connected_at", e.getValue().getConnectedAt().toString(),
                                "last_heartbeat", e.getValue().getLastHeartbeat() != null
                                        ? e.getValue().getLastHeartbeat().toString() : "N/A",
                                "alive", e.getValue().isAlive()
                        )).toList()
        ));
    }

    private String getUptime() {
        long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        long seconds = uptime / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
    }
}
