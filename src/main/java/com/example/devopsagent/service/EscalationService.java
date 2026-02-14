package com.example.devopsagent.service;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.notification.NotificationService;
import com.example.devopsagent.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Escalation Engine - Automatically escalates unacknowledged incidents
 * through configured notification tiers at defined intervals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

    private final IncidentRepository incidentRepository;
    private final NotificationService notificationService;
    private final AgentProperties properties;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AuditService auditService;

    /**
     * Runs every 60 seconds, checking for incidents that need escalation.
     */
    @Scheduled(fixedDelay = 60000)
    public void checkEscalations() {
        List<Incident> openIncidents = incidentRepository.findByStatusIn(
                List.of(Incident.IncidentStatus.OPEN, Incident.IncidentStatus.INVESTIGATING));

        int timeoutMinutes = properties.getIncidents().getEscalationTimeoutMinutes();
        if (timeoutMinutes <= 0) return;

        List<AgentProperties.IncidentConfig.EscalationTier> tiers =
                properties.getIncidents().getEscalationTiers();

        Instant now = Instant.now();

        for (Incident incident : openIncidents) {
            // Skip acknowledged incidents
            if (incident.getAcknowledgedAt() != null) continue;
            if (incident.getCreatedAt() == null) continue;

            // Calculate how many escalation periods have passed
            long minutesSinceCreation = Duration.between(incident.getCreatedAt(), now).toMinutes();
            int expectedEscalations = (int) (minutesSinceCreation / timeoutMinutes);

            if (expectedEscalations > incident.getEscalationCount() && expectedEscalations > 0) {
                int newTier = Math.min(expectedEscalations, tiers.isEmpty() ? 3 : tiers.size());
                escalateIncident(incident, newTier, tiers);
            }
        }
    }

    private void escalateIncident(Incident incident, int tierNumber,
                                   List<AgentProperties.IncidentConfig.EscalationTier> tiers) {
        incident.setEscalationCount(tierNumber);
        incidentRepository.save(incident);

        String tierLabel;
        String channel;
        if (!tiers.isEmpty() && tierNumber <= tiers.size()) {
            AgentProperties.IncidentConfig.EscalationTier tier = tiers.get(tierNumber - 1);
            tierLabel = tier.getLabel();
            channel = tier.getChannel();
        } else {
            tierLabel = "Escalation Level " + tierNumber;
            channel = "slack";
        }

        log.warn("Escalating incident {} to tier {} ({}): {}",
                incident.getId(), tierNumber, tierLabel, incident.getTitle());

        // Send escalation notification
        notificationService.sendCustomNotification(channel,
                String.format(":rotating_light: *ESCALATION (Tier %d - %s)*\n" +
                              "*Incident:* %s\n*Service:* %s\n*Severity:* %s\n" +
                              "*Open for:* %d minutes\n*ID:* %s",
                        tierNumber, tierLabel, incident.getTitle(),
                        incident.getService(), incident.getSeverity(),
                        Duration.between(incident.getCreatedAt(), Instant.now()).toMinutes(),
                        incident.getId()));

        // Broadcast via WebSocket
        gatewayHandler.broadcast("incident.escalated", Map.of(
                "incident_id", incident.getId(),
                "tier", tierNumber,
                "tier_label", tierLabel,
                "channel", channel,
                "title", incident.getTitle(),
                "service", incident.getService() != null ? incident.getService() : ""
        ));

        // Audit
        auditService.log("system", "ESCALATION", incident.getId(),
                Map.of("tier", tierNumber, "tier_label", tierLabel, "channel", channel));
    }
}
