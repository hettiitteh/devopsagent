package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Incident Management Tool - Create, update, and manage incidents.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentManageTool implements AgentTool {

    private final IncidentRepository incidentRepository;

    @Override
    public String getName() { return "incident_manage"; }

    @Override
    public String getDescription() {
        return "Create, update, and manage production incidents. " +
               "Supports creating new incidents, updating status/severity, " +
               "adding root cause analysis, and resolving incidents. " +
               "Use when you detect an issue that needs tracking and coordination.";
    }

    @Override
    public String getCategory() { return "incident"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of("type", "string", "description",
                    "Action: create, update, resolve, list, get"),
                "incident_id", Map.of("type", "string", "description",
                    "Incident ID (for update/resolve/get actions)"),
                "title", Map.of("type", "string", "description",
                    "Incident title (for create)"),
                "description", Map.of("type", "string", "description",
                    "Incident description"),
                "severity", Map.of("type", "string", "description",
                    "Severity: CRITICAL, HIGH, MEDIUM, LOW, INFO"),
                "service", Map.of("type", "string", "description",
                    "Affected service name"),
                "root_cause", Map.of("type", "string", "description",
                    "Root cause analysis"),
                "resolution", Map.of("type", "string", "description",
                    "Resolution details")
            ),
            "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String action = (String) parameters.get("action");

        return switch (action.toLowerCase()) {
            case "create" -> createIncident(parameters);
            case "update" -> updateIncident(parameters);
            case "resolve" -> resolveIncident(parameters);
            case "list" -> listIncidents(parameters);
            case "get" -> getIncident(parameters);
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    private ToolResult createIncident(Map<String, Object> params) {
        Incident incident = Incident.builder()
                .title((String) params.getOrDefault("title", "Untitled Incident"))
                .description((String) params.getOrDefault("description", ""))
                .severity(Incident.Severity.valueOf(
                        ((String) params.getOrDefault("severity", "MEDIUM")).toUpperCase()))
                .status(Incident.IncidentStatus.OPEN)
                .service((String) params.getOrDefault("service", "unknown"))
                .source("sre-agent")
                .build();

        incident = incidentRepository.save(incident);
        log.info("Created incident: {} - {}", incident.getId(), incident.getTitle());

        return ToolResult.text(String.format(
                "Incident Created:\nID: %s\nTitle: %s\nSeverity: %s\nService: %s\nStatus: %s",
                incident.getId(), incident.getTitle(), incident.getSeverity(),
                incident.getService(), incident.getStatus()));
    }

    private ToolResult updateIncident(Map<String, Object> params) {
        String id = (String) params.get("incident_id");
        if (id == null) return ToolResult.error("incident_id is required for update action");

        return incidentRepository.findById(id)
                .map(incident -> {
                    if (params.containsKey("severity")) {
                        incident.setSeverity(Incident.Severity.valueOf(((String) params.get("severity")).toUpperCase()));
                    }
                    if (params.containsKey("description")) {
                        incident.setDescription((String) params.get("description"));
                    }
                    if (params.containsKey("root_cause")) {
                        incident.setRootCause((String) params.get("root_cause"));
                    }
                    incidentRepository.save(incident);
                    return ToolResult.text("Incident " + id + " updated successfully.");
                })
                .orElse(ToolResult.error("Incident not found: " + id));
    }

    private ToolResult resolveIncident(Map<String, Object> params) {
        String id = (String) params.get("incident_id");
        if (id == null) return ToolResult.error("incident_id is required for resolve action");

        return incidentRepository.findById(id)
                .map(incident -> {
                    incident.setStatus(Incident.IncidentStatus.RESOLVED);
                    incident.setResolvedAt(Instant.now());
                    if (params.containsKey("resolution")) {
                        incident.setResolution((String) params.get("resolution"));
                    }
                    if (params.containsKey("root_cause")) {
                        incident.setRootCause((String) params.get("root_cause"));
                    }
                    incidentRepository.save(incident);
                    return ToolResult.text(String.format(
                            "Incident %s resolved.\nResolution: %s",
                            id, incident.getResolution()));
                })
                .orElse(ToolResult.error("Incident not found: " + id));
    }

    private ToolResult listIncidents(Map<String, Object> params) {
        List<Incident> incidents = incidentRepository.findActiveIncidents();
        if (incidents.isEmpty()) {
            return ToolResult.text("No active incidents.");
        }

        StringBuilder sb = new StringBuilder("Active Incidents:\n");
        for (Incident incident : incidents) {
            sb.append(String.format("- [%s] %s | %s | %s | %s\n",
                    incident.getSeverity(), incident.getId(),
                    incident.getTitle(), incident.getService(), incident.getStatus()));
        }
        return ToolResult.text(sb.toString());
    }

    private ToolResult getIncident(Map<String, Object> params) {
        String id = (String) params.get("incident_id");
        if (id == null) return ToolResult.error("incident_id is required for get action");

        return incidentRepository.findById(id)
                .map(incident -> ToolResult.text(String.format(
                        "Incident Details:\nID: %s\nTitle: %s\nSeverity: %s\nStatus: %s\nService: %s\n" +
                        "Description: %s\nRoot Cause: %s\nResolution: %s\nCreated: %s\nUpdated: %s",
                        incident.getId(), incident.getTitle(), incident.getSeverity(), incident.getStatus(),
                        incident.getService(), incident.getDescription(), incident.getRootCause(),
                        incident.getResolution(), incident.getCreatedAt(), incident.getUpdatedAt())))
                .orElse(ToolResult.error("Incident not found: " + id));
    }
}
