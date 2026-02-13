package com.example.devopsagent.notification;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * Notification Service - Send alerts via Slack, PagerDuty, email, etc.
 * Like OpenClaw's multi-channel messaging, supports multiple notification backends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AgentProperties properties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final MediaType JSON = MediaType.get("application/json");

    /**
     * Send notifications for an incident based on severity.
     */
    @Async("agentExecutor")
    public void notifyIncident(Incident incident) {
        log.info("Sending notifications for incident {} (severity: {})",
                incident.getId(), incident.getSeverity());

        // Slack notification
        if (properties.getNotifications().getSlack().isEnabled()) {
            sendSlackNotification(incident);
        }

        // PagerDuty for critical/high severity
        if (properties.getNotifications().getPagerduty().isEnabled()
                && (incident.getSeverity() == Incident.Severity.CRITICAL
                || incident.getSeverity() == Incident.Severity.HIGH)) {
            sendPagerDutyNotification(incident);
        }

        // Email notification
        if (properties.getNotifications().getEmail().isEnabled()) {
            sendEmailNotification(incident);
        }
    }

    /**
     * Send a Slack notification.
     */
    public void sendSlackNotification(Incident incident) {
        String webhookUrl = properties.getNotifications().getSlack().getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Slack webhook URL not configured");
            return;
        }

        try {
            String emoji = switch (incident.getSeverity()) {
                case CRITICAL -> ":rotating_light:";
                case HIGH -> ":warning:";
                case MEDIUM -> ":large_orange_diamond:";
                case LOW -> ":information_source:";
                case INFO -> ":speech_balloon:";
            };

            Map<String, Object> payload = Map.of(
                    "text", String.format("%s *[%s] Incident: %s*\nService: %s\nDescription: %s\nID: %s",
                            emoji, incident.getSeverity(), incident.getTitle(),
                            incident.getService(), incident.getDescription(), incident.getId()),
                    "username", "DevOps SRE Agent",
                    "icon_emoji", ":robot_face:"
            );

            String json = objectMapper.writeValueAsString(payload);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("Slack notification sent for incident {}", incident.getId());
                } else {
                    log.error("Slack notification failed: {}", response.code());
                }
            }
        } catch (IOException e) {
            log.error("Failed to send Slack notification: {}", e.getMessage());
        }
    }

    /**
     * Send a PagerDuty notification.
     */
    public void sendPagerDutyNotification(Incident incident) {
        String apiKey = properties.getNotifications().getPagerduty().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("PagerDuty API key not configured");
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "routing_key", apiKey,
                    "event_action", "trigger",
                    "payload", Map.of(
                            "summary", String.format("[%s] %s - %s",
                                    incident.getSeverity(), incident.getTitle(), incident.getService()),
                            "severity", incident.getSeverity().name().toLowerCase(),
                            "source", "devops-sre-agent",
                            "component", incident.getService(),
                            "custom_details", Map.of(
                                    "incident_id", incident.getId(),
                                    "description", incident.getDescription() != null ? incident.getDescription() : ""
                            )
                    )
            );

            String json = objectMapper.writeValueAsString(payload);
            Request request = new Request.Builder()
                    .url("https://events.pagerduty.com/v2/enqueue")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("PagerDuty notification sent for incident {}", incident.getId());
                } else {
                    log.error("PagerDuty notification failed: {}", response.code());
                }
            }
        } catch (IOException e) {
            log.error("Failed to send PagerDuty notification: {}", e.getMessage());
        }
    }

    /**
     * Send an email notification (placeholder - integrate with JavaMail).
     */
    public void sendEmailNotification(Incident incident) {
        log.info("Email notification would be sent for incident {} (not yet implemented)", incident.getId());
        // TODO: Implement with JavaMail or SendGrid
    }

    /**
     * Send a custom notification message.
     */
    @Async("agentExecutor")
    public void sendCustomNotification(String channel, String message) {
        switch (channel.toLowerCase()) {
            case "slack" -> {
                if (!properties.getNotifications().getSlack().isEnabled()) return;
                try {
                    Map<String, Object> payload = Map.of("text", message);
                    String json = objectMapper.writeValueAsString(payload);
                    Request request = new Request.Builder()
                            .url(properties.getNotifications().getSlack().getWebhookUrl())
                            .post(RequestBody.create(json, JSON))
                            .build();
                    httpClient.newCall(request).execute().close();
                } catch (IOException e) {
                    log.error("Failed to send custom Slack notification: {}", e.getMessage());
                }
            }
            default -> log.warn("Unknown notification channel: {}", channel);
        }
    }
}
