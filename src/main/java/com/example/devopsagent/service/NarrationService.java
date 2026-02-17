package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.agent.LlmClient;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM Narration Service — generates natural-language commentary for events
 * and broadcasts them to the chat so Jarvis feels conversational and proactive.
 */
@Slf4j
@Service
public class NarrationService {

    private final LlmClient llmClient;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AgentProperties properties;

    private static final String SYSTEM_PROMPT =
            "You are Jarvis the SRE Engineer. Briefly narrate this event to the user in 1-2 conversational sentences. " +
            "Be concise, professional, and helpful. Do not use markdown formatting.";

    public NarrationService(@Lazy LlmClient llmClient,
                            GatewayWebSocketHandler gatewayHandler,
                            AgentProperties properties) {
        this.llmClient = llmClient;
        this.gatewayHandler = gatewayHandler;
        this.properties = properties;
    }

    @Async
    public void narrateIncidentCreated(Incident incident, String triageReasoning) {
        if (!properties.getNarration().isEnabled()) return;
        String details = String.format(
                "Event: Incident created\nService: %s\nTitle: %s\nSeverity: %s\nDescription: %s%s",
                incident.getService(), incident.getTitle(), incident.getSeverity(),
                truncate(incident.getDescription(), 300),
                triageReasoning != null ? "\nTriage reasoning: " + triageReasoning : "");
        broadcastNarration(details, "incident_created");
    }

    @Async
    public void narratePlaybookTriggered(String playbookName, String serviceName, String incidentId) {
        if (!properties.getNarration().isEnabled()) return;
        String details = String.format(
                "Event: Playbook auto-triggered\nPlaybook: %s\nService: %s\nIncident: %s",
                playbookName, serviceName, incidentId != null ? incidentId : "N/A");
        broadcastNarration(details, "playbook_triggered");
    }

    @Async
    public void narratePlaybookCompleted(String playbookName, boolean success, long durationMs, String output) {
        if (!properties.getNarration().isEnabled()) return;
        String details = String.format(
                "Event: Playbook completed\nPlaybook: %s\nResult: %s\nDuration: %dms\nOutput summary: %s",
                playbookName, success ? "SUCCESS" : "FAILED", durationMs, truncate(output, 300));
        broadcastNarration(details, "playbook_completed");
    }

    @Async
    public void narrateServiceRecovered(String serviceName, long downtimeMs) {
        if (!properties.getNarration().isEnabled()) return;
        String downtime = downtimeMs > 0 ? String.format(" after %d seconds of downtime", downtimeMs / 1000) : "";
        String details = String.format(
                "Event: Service recovered\nService: %s%s",
                serviceName, downtime);
        broadcastNarration(details, "service_recovered");
    }

    @Async
    public void narrateAlertTriggered(String ruleName, String metric, double value, double threshold, String message) {
        if (!properties.getNarration().isEnabled()) return;
        String details = String.format(
                "Event: Alert triggered\nRule: %s\nMetric: %s\nCurrent value: %.2f\nThreshold: %.2f\nMessage: %s",
                ruleName, metric, value, threshold, message);
        broadcastNarration(details, "alert_triggered");
    }

    private void broadcastNarration(String eventDetails, String eventType) {
        try {
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.system(SYSTEM_PROMPT));
            messages.add(AgentMessage.user(eventDetails));

            AgentMessage response = llmClient.chat(messages, List.of());
            String narration = response.getContent();
            if (narration != null && !narration.isBlank()) {
                gatewayHandler.broadcast("jarvis.narration", Map.of(
                        "message", narration,
                        "event_type", eventType,
                        "timestamp", Instant.now().toString()
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to generate narration for {}: {}", eventType, e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
