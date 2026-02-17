package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentEngine;
import com.example.devopsagent.agent.SystemPromptBuilder;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autonomous Investigation Service — when critical events happen,
 * Jarvis starts its own agent session and streams the investigation to the chat.
 */
@Slf4j
@Service
public class AutonomousInvestigationService {

    private final AgentEngine agentEngine;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AgentProperties properties;

    /** Debounce: track recent investigations per service to avoid storms. */
    private final Map<String, Instant> recentInvestigations = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_MS = 5 * 60 * 1000; // 5 minutes

    public AutonomousInvestigationService(@Lazy AgentEngine agentEngine,
                                          GatewayWebSocketHandler gatewayHandler,
                                          AgentProperties properties) {
        this.agentEngine = agentEngine;
        this.gatewayHandler = gatewayHandler;
        this.properties = properties;
    }

    @Async
    public void investigateIncident(Incident incident) {
        if (!properties.getAutonomous().isEnabled() || !properties.getAutonomous().isOnIncident()) return;
        if (!meetsMinSeverity(incident.getSeverity())) return;
        if (isDebounced(incident.getService())) return;

        String sessionId = "auto-" + incident.getId();
        String title = incident.getTitle();
        String prompt = String.format(
                "An incident has been detected: '%s' on service '%s'. Severity: %s. " +
                "Error: %s. Investigate this issue: check health, examine logs, " +
                "check Docker/K8s status, and attempt remediation. Report your findings concisely.",
                incident.getTitle(), incident.getService(), incident.getSeverity(),
                truncate(incident.getDescription(), 500));

        runInvestigation(sessionId, title, prompt, incident.getService());
    }

    @Async
    public void investigateAlert(String ruleName, String metric, double value, String message) {
        if (!properties.getAutonomous().isEnabled() || !properties.getAutonomous().isOnAlert()) return;
        if (isDebounced("alert:" + ruleName)) return;

        String sessionId = "auto-alert-" + ruleName + "-" + System.currentTimeMillis();
        String title = "Alert: " + ruleName;
        String prompt = String.format(
                "An alert has fired: '%s'. Metric: %s, current value: %.2f. Message: %s. " +
                "Investigate what caused this alert, check relevant service health and logs, " +
                "and suggest remediation. Report your findings concisely.",
                ruleName, metric, value, message);

        runInvestigation(sessionId, title, prompt, "alert:" + ruleName);
    }

    @Async
    public void investigatePlaybookFailure(String playbookName, String serviceName, String output) {
        if (!properties.getAutonomous().isEnabled() || !properties.getAutonomous().isOnPlaybookFailure()) return;
        if (isDebounced("playbook:" + playbookName)) return;

        String sessionId = "auto-pb-" + playbookName + "-" + System.currentTimeMillis();
        String title = "Playbook failed: " + playbookName;
        String prompt = String.format(
                "Playbook '%s' failed for service '%s'. Output: %s. " +
                "Investigate why the playbook failed, check the service status, " +
                "and determine if manual intervention is needed. Report your findings concisely.",
                playbookName, serviceName, truncate(output, 500));

        runInvestigation(sessionId, title, prompt, "playbook:" + playbookName);
    }

    private void runInvestigation(String sessionId, String title, String prompt, String debounceKey) {
        recordDebounce(debounceKey);

        log.info("Starting autonomous investigation: {} (session: {})", title, sessionId);

        // Broadcast investigation started
        gatewayHandler.broadcast("jarvis.autonomous.started", Map.of(
                "session_id", sessionId,
                "title", title,
                "timestamp", Instant.now().toString()
        ));

        try {
            SystemPromptBuilder.AgentContext context = SystemPromptBuilder.AgentContext.builder()
                    .additionalContext("Autonomous investigation: " + title)
                    .build();

            agentEngine.run(sessionId, prompt, context)
                    .thenAccept(response -> {
                        gatewayHandler.broadcast("jarvis.autonomous.completed", Map.of(
                                "session_id", sessionId,
                                "response", response.getResponse() != null ? response.getResponse() : "Investigation complete.",
                                "tools_used", response.getToolsUsed() != null ? response.getToolsUsed() : java.util.List.of(),
                                "timestamp", Instant.now().toString()
                        ));
                        log.info("Autonomous investigation completed: {} ({} iterations)", sessionId, response.getIterations());
                    })
                    .exceptionally(ex -> {
                        log.error("Autonomous investigation failed: {}", sessionId, ex);
                        gatewayHandler.broadcast("jarvis.autonomous.completed", Map.of(
                                "session_id", sessionId,
                                "response", "Investigation encountered an error: " + ex.getMessage(),
                                "timestamp", Instant.now().toString()
                        ));
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to start autonomous investigation: {}", sessionId, e);
        }
    }

    private boolean meetsMinSeverity(Incident.Severity severity) {
        String minSeverity = properties.getAutonomous().getMinSeverity();
        int min = severityOrdinal(minSeverity);
        int actual = severityOrdinal(severity.name());
        return actual >= min;
    }

    private int severityOrdinal(String severity) {
        return switch (severity.toUpperCase()) {
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CRITICAL" -> 4;
            default -> 0;
        };
    }

    private boolean isDebounced(String key) {
        Instant last = recentInvestigations.get(key);
        if (last != null && Instant.now().toEpochMilli() - last.toEpochMilli() < DEBOUNCE_MS) {
            log.debug("Autonomous investigation debounced for key: {}", key);
            return true;
        }
        return false;
    }

    private void recordDebounce(String key) {
        recentInvestigations.put(key, Instant.now());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
