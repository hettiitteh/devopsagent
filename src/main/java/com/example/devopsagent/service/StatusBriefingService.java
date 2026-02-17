package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.agent.LlmClient;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.MonitoredService;
import com.example.devopsagent.domain.PlaybookExecution;
import com.example.devopsagent.domain.RcaReport;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import com.example.devopsagent.repository.PlaybookExecutionRepository;
import com.example.devopsagent.repository.RcaReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Status Briefing Service — periodic and on-demand status summaries posted to chat.
 * Provides the "heartbeat" that makes Jarvis feel alive even during quiet periods.
 */
@Slf4j
@Service
public class StatusBriefingService {

    private final IncidentRepository incidentRepository;
    private final MonitoredServiceRepository serviceRepository;
    private final PlaybookExecutionRepository executionRepository;
    private final RcaReportRepository rcaRepository;
    private final LlmClient llmClient;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AgentProperties properties;

    private volatile Instant lastBriefingAt = Instant.now();
    private volatile Instant lastQuietBriefingAt = Instant.now();

    public StatusBriefingService(IncidentRepository incidentRepository,
                                 MonitoredServiceRepository serviceRepository,
                                 PlaybookExecutionRepository executionRepository,
                                 RcaReportRepository rcaRepository,
                                 @Lazy LlmClient llmClient,
                                 GatewayWebSocketHandler gatewayHandler,
                                 AgentProperties properties) {
        this.incidentRepository = incidentRepository;
        this.serviceRepository = serviceRepository;
        this.executionRepository = executionRepository;
        this.rcaRepository = rcaRepository;
        this.llmClient = llmClient;
        this.gatewayHandler = gatewayHandler;
        this.properties = properties;
    }

    /**
     * Periodic briefing — runs every minute but only emits when the configured interval has elapsed.
     */
    @Scheduled(fixedDelay = 60000)
    public void periodicBriefing() {
        if (!properties.getBriefing().isEnabled()) return;
        if (gatewayHandler.getActiveSessionCount() == 0) return;

        int intervalMinutes = properties.getBriefing().getIntervalMinutes();
        int quietMinutes = properties.getBriefing().getQuietIntervalMinutes();

        StatusSnapshot snapshot = gatherSnapshot(Instant.now().minus(intervalMinutes, ChronoUnit.MINUTES));

        if (snapshot.hasActivity()) {
            if (Instant.now().isAfter(lastBriefingAt.plus(intervalMinutes, ChronoUnit.MINUTES))) {
                String briefing = generateBriefing(snapshot);
                broadcastBriefing(briefing);
                lastBriefingAt = Instant.now();
                lastQuietBriefingAt = Instant.now();
            }
        } else {
            if (Instant.now().isAfter(lastQuietBriefingAt.plus(quietMinutes, ChronoUnit.MINUTES))) {
                broadcastBriefing("All systems healthy. No incidents or alerts in the last " + quietMinutes + " minutes.");
                lastQuietBriefingAt = Instant.now();
            }
        }
    }

    /**
     * Generate a reconnection briefing for a user who just connected.
     * Summarizes events from the last hour.
     */
    public String getReconnectionBriefing() {
        if (!properties.getBriefing().isEnabled()) return null;

        StatusSnapshot snapshot = gatherSnapshot(Instant.now().minus(1, ChronoUnit.HOURS));
        if (!snapshot.hasActivity()) {
            return "All systems healthy. No incidents or alerts in the last hour.";
        }
        return generateBriefing(snapshot);
    }

    private StatusSnapshot gatherSnapshot(Instant since) {
        List<MonitoredService> allServices = serviceRepository.findByEnabled(true);
        long healthyCount = allServices.stream()
                .filter(s -> s.getHealthStatus() == MonitoredService.HealthStatus.HEALTHY).count();
        long unhealthyCount = allServices.size() - healthyCount;

        List<Incident> activeIncidents = incidentRepository.findByStatusIn(
                List.of(Incident.IncidentStatus.OPEN, Incident.IncidentStatus.INVESTIGATING,
                        Incident.IncidentStatus.ACKNOWLEDGED, Incident.IncidentStatus.MITIGATING));

        List<Incident> recentIncidents = incidentRepository.findRecentIncidents(since);

        List<PlaybookExecution> recentExecutions = executionRepository.findAll().stream()
                .filter(e -> e.getStartedAt() != null && e.getStartedAt().isAfter(since))
                .toList();

        List<RcaReport> pendingRcas = rcaRepository.findByStatus(RcaReport.RcaStatus.PENDING_REVIEW);

        return new StatusSnapshot(
                allServices.size(), healthyCount, unhealthyCount,
                activeIncidents, recentIncidents, recentExecutions, pendingRcas);
    }

    private String generateBriefing(StatusSnapshot snapshot) {
        try {
            String prompt = buildBriefingPrompt(snapshot);
            List<AgentMessage> messages = new ArrayList<>();
            messages.add(AgentMessage.system(
                    "You are Jarvis the SRE Engineer. Generate a concise 2-3 sentence status briefing " +
                    "for the user based on the current system state. Be conversational and professional. " +
                    "Do not use markdown formatting."));
            messages.add(AgentMessage.user(prompt));

            AgentMessage response = llmClient.chat(messages, List.of());
            return response.getContent() != null ? response.getContent() : "Status briefing unavailable.";
        } catch (Exception e) {
            log.warn("Failed to generate LLM briefing: {}", e.getMessage());
            return buildFallbackBriefing(snapshot);
        }
    }

    private String buildBriefingPrompt(StatusSnapshot snapshot) {
        StringBuilder sb = new StringBuilder("Current system status:\n");
        sb.append(String.format("- Total services: %d (%d healthy, %d unhealthy)\n",
                snapshot.totalServices, snapshot.healthyCount, snapshot.unhealthyCount));
        sb.append(String.format("- Active incidents: %d\n", snapshot.activeIncidents.size()));

        if (!snapshot.activeIncidents.isEmpty()) {
            sb.append("- Active incident details:\n");
            for (Incident i : snapshot.activeIncidents.subList(0, Math.min(5, snapshot.activeIncidents.size()))) {
                sb.append(String.format("  - [%s] %s on %s (%s)\n",
                        i.getSeverity(), i.getTitle(), i.getService(), i.getStatus()));
            }
        }

        sb.append(String.format("- Recent incidents: %d\n", snapshot.recentIncidents.size()));
        sb.append(String.format("- Recent playbook executions: %d", snapshot.recentExecutions.size()));

        long pbSuccess = snapshot.recentExecutions.stream()
                .filter(e -> e.getStatus() == PlaybookExecution.ExecutionStatus.SUCCESS).count();
        long pbFailed = snapshot.recentExecutions.stream()
                .filter(e -> e.getStatus() == PlaybookExecution.ExecutionStatus.FAILED).count();
        if (!snapshot.recentExecutions.isEmpty()) {
            sb.append(String.format(" (%d success, %d failed)", pbSuccess, pbFailed));
        }
        sb.append("\n");

        if (!snapshot.pendingRcas.isEmpty()) {
            sb.append(String.format("- Pending RCA reviews: %d\n", snapshot.pendingRcas.size()));
        }

        return sb.toString();
    }

    private String buildFallbackBriefing(StatusSnapshot snapshot) {
        if (snapshot.activeIncidents.isEmpty() && snapshot.unhealthyCount == 0) {
            return "All " + snapshot.totalServices + " services healthy. No active incidents.";
        }
        return String.format("%d/%d services healthy. %d active incident(s). %d playbook execution(s) recently.",
                snapshot.healthyCount, snapshot.totalServices,
                snapshot.activeIncidents.size(), snapshot.recentExecutions.size());
    }

    private void broadcastBriefing(String message) {
        gatewayHandler.broadcast("jarvis.briefing", Map.of(
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }

    /** Sends a briefing to a specific WebSocket session (for reconnection). */
    public void sendBriefingToSession(String sessionId) {
        String briefing = getReconnectionBriefing();
        if (briefing != null) {
            gatewayHandler.sendToSession(sessionId, "jarvis.briefing", Map.of(
                    "message", briefing,
                    "timestamp", Instant.now().toString()
            ));
        }
    }

    private record StatusSnapshot(
            long totalServices, long healthyCount, long unhealthyCount,
            List<Incident> activeIncidents, List<Incident> recentIncidents,
            List<PlaybookExecution> recentExecutions, List<RcaReport> pendingRcas) {

        boolean hasActivity() {
            return !activeIncidents.isEmpty() || !recentIncidents.isEmpty()
                    || !recentExecutions.isEmpty() || unhealthyCount > 0;
        }
    }
}
