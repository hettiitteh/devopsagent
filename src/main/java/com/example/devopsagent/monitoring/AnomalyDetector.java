package com.example.devopsagent.monitoring;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.AlertRule;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.repository.AlertRuleRepository;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.service.AutonomousInvestigationService;
import com.example.devopsagent.service.NarrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anomaly Detector - Evaluates alert rules against current metrics.
 * Detects threshold violations, rate changes, and metric absence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyDetector {

    private final AlertRuleRepository alertRuleRepository;
    private final IncidentRepository incidentRepository;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AgentProperties properties;
    private final NarrationService narrationService;
    private final AutonomousInvestigationService autonomousInvestigationService;

    // Current metric values (populated by MetricsCollector or external sources)
    private final Map<String, Double> currentMetrics = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastMetricUpdate = new ConcurrentHashMap<>();

    /**
     * Evaluate all active alert rules.
     */
    @Scheduled(fixedDelayString = "${devops-agent.monitoring.poll-interval-seconds:30}000")
    public void evaluateAlertRules() {
        if (!properties.getMonitoring().isAnomalyDetectionEnabled()) return;

        List<AlertRule> rules = alertRuleRepository.findByEnabled(true);
        for (AlertRule rule : rules) {
            try {
                evaluateRule(rule);
            } catch (Exception e) {
                log.error("Error evaluating alert rule {}: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /**
     * Evaluate a single alert rule.
     */
    public boolean evaluateRule(AlertRule rule) {
        String metric = rule.getMetric();
        Double currentValue = currentMetrics.get(metric);

        // Check for ABSENT condition
        if (rule.getCondition() == AlertRule.Condition.ABSENT) {
            Instant lastUpdate = lastMetricUpdate.get(metric);
            if (lastUpdate == null || lastUpdate.isBefore(Instant.now().minusSeconds(rule.getDurationSeconds()))) {
                fireAlert(rule, 0.0, "Metric absent for > " + rule.getDurationSeconds() + "s");
                return true;
            }
            return false;
        }

        if (currentValue == null) return false;

        boolean triggered = switch (rule.getCondition()) {
            case GREATER_THAN -> currentValue > rule.getThreshold();
            case LESS_THAN -> currentValue < rule.getThreshold();
            case EQUALS -> currentValue == rule.getThreshold();
            case NOT_EQUALS -> currentValue != rule.getThreshold();
            case GREATER_THAN_OR_EQUAL -> currentValue >= rule.getThreshold();
            case LESS_THAN_OR_EQUAL -> currentValue <= rule.getThreshold();
            default -> false;
        };

        if (triggered) {
            // Check cooldown
            if (rule.getLastTriggeredAt() != null) {
                Instant cooldownEnd = rule.getLastTriggeredAt()
                        .plusSeconds(rule.getCooldownMinutes() * 60L);
                if (Instant.now().isBefore(cooldownEnd)) {
                    log.debug("Alert rule {} in cooldown period", rule.getId());
                    return false;
                }
            }

            fireAlert(rule, currentValue,
                    String.format("Metric %s is %s (threshold: %s, current: %s)",
                            metric, rule.getCondition(), rule.getThreshold(), currentValue));
            return true;
        }

        return false;
    }

    /**
     * Fire an alert and create an incident.
     */
    private void fireAlert(AlertRule rule, double currentValue, String message) {
        log.warn("Alert triggered: {} - {}", rule.getName(), message);

        // Update rule state
        rule.setLastTriggeredAt(Instant.now());
        rule.setTriggerCount(rule.getTriggerCount() + 1);
        alertRuleRepository.save(rule);

        // Create incident
        Incident incident = Incident.builder()
                .title("Alert: " + rule.getName())
                .description(message)
                .severity(rule.getSeverity())
                .status(Incident.IncidentStatus.OPEN)
                .service(rule.getServiceName())
                .source("anomaly-detector")
                .playbookId(rule.getPlaybookId())
                .build();
        incidentRepository.save(incident);

        // Broadcast alert via gateway
        gatewayHandler.broadcast("alert.triggered", Map.of(
                "rule_id", rule.getId(),
                "rule_name", rule.getName(),
                "metric", rule.getMetric(),
                "current_value", currentValue,
                "threshold", rule.getThreshold(),
                "severity", rule.getSeverity().name(),
                "incident_id", incident.getId(),
                "message", message,
                "timestamp", Instant.now().toString()
        ));

        // Narrate the alert via LLM
        narrationService.narrateAlertTriggered(rule.getName(), rule.getMetric(), currentValue, rule.getThreshold(), message);

        // Launch autonomous investigation for the alert
        autonomousInvestigationService.investigateAlert(rule.getName(), rule.getMetric(), currentValue, message);
    }

    /**
     * Update a metric value (called by metrics collectors).
     */
    public void updateMetric(String metricName, double value) {
        currentMetrics.put(metricName, value);
        lastMetricUpdate.put(metricName, Instant.now());
    }

    /**
     * Get all current metric values.
     */
    public Map<String, Double> getCurrentMetrics() {
        return Map.copyOf(currentMetrics);
    }
}
