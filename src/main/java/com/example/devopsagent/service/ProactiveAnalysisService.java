package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.agent.LlmClient;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.MonitoredService;
import com.example.devopsagent.domain.RiskAssessment;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.monitoring.AnomalyDetector;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import com.example.devopsagent.repository.RiskAssessmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Proactive Analysis Service - Uses LLM to analyze system state on a schedule
 * and identify emerging risks before they become incidents.
 *
 * This replaces the purely reactive monitoring approach with proactive SRE intelligence:
 * - Detects flapping services
 * - Identifies concerning trends
 * - Spots resource exhaustion patterns
 * - Predicts potential failures
 */
@Slf4j
@Service
public class ProactiveAnalysisService {

    private final AgentProperties properties;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final MonitoredServiceRepository serviceRepository;
    private final IncidentRepository incidentRepository;
    private final ResolutionRecordRepository resolutionRecordRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final AnomalyDetector anomalyDetector;
    private final LearningService learningService;
    private final GatewayWebSocketHandler gatewayHandler;

    public ProactiveAnalysisService(AgentProperties properties,
                                     @Lazy LlmClient llmClient,
                                     ObjectMapper objectMapper,
                                     MonitoredServiceRepository serviceRepository,
                                     IncidentRepository incidentRepository,
                                     ResolutionRecordRepository resolutionRecordRepository,
                                     RiskAssessmentRepository riskAssessmentRepository,
                                     AnomalyDetector anomalyDetector,
                                     LearningService learningService,
                                     GatewayWebSocketHandler gatewayHandler) {
        this.properties = properties;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.serviceRepository = serviceRepository;
        this.incidentRepository = incidentRepository;
        this.resolutionRecordRepository = resolutionRecordRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.anomalyDetector = anomalyDetector;
        this.learningService = learningService;
        this.gatewayHandler = gatewayHandler;
    }

    /**
     * Scheduled proactive analysis -- runs at the configured interval.
     * The default fixedDelay is 30 minutes (1800000ms).
     * The actual interval is controlled by the config property.
     */
    @Scheduled(fixedDelayString = "#{${devops-agent.proactive-analysis.interval-minutes:30} * 60 * 1000}")
    public void scheduledAnalysis() {
        if (!properties.getProactiveAnalysis().isEnabled()) {
            return;
        }
        try {
            analyzeSystemRisks();
        } catch (Exception e) {
            log.error("Proactive analysis failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Gather a system snapshot, send it to the LLM, and persist any new risks.
     * Can also be called manually from the REST API.
     */
    public List<RiskAssessment> analyzeSystemRisks() {
        log.info("Starting proactive risk analysis...");

        // 1. Gather snapshot
        String snapshot = buildSystemSnapshot();

        // 2. Build LLM prompt
        String prompt = buildAnalysisPrompt(snapshot);

        // 3. Call LLM
        List<RiskAssessment> newRisks = new ArrayList<>();
        try {
            List<AgentMessage> messages = List.of(
                    AgentMessage.system(prompt),
                    AgentMessage.user("Analyze the current system state and identify any emerging risks.")
            );
            AgentMessage response = llmClient.chat(messages, List.of());
            String content = response.getContent();

            if (content != null && !content.isBlank()) {
                content = content.trim();
                if (content.startsWith("```")) {
                    content = content.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                }

                List<Map<String, String>> riskMaps = objectMapper.readValue(
                        content, new TypeReference<List<Map<String, String>>>() {});

                for (Map<String, String> riskMap : riskMaps) {
                    String title = riskMap.getOrDefault("title", "Unknown Risk");
                    String service = riskMap.get("service");
                    if ("null".equalsIgnoreCase(service) || (service != null && service.isBlank())) {
                        service = null;
                    }

                    // Deduplicate: skip if similar open risk already exists
                    if (riskAssessmentRepository.existsByRiskTitleAndServiceAndStatus(
                            title, service, RiskAssessment.Status.OPEN)) {
                        log.debug("Skipping duplicate risk: {} for service {}", title, service);
                        continue;
                    }

                    RiskAssessment.Severity severity;
                    try {
                        severity = RiskAssessment.Severity.valueOf(
                                riskMap.getOrDefault("severity", "MEDIUM").toUpperCase());
                    } catch (IllegalArgumentException e) {
                        severity = RiskAssessment.Severity.MEDIUM;
                    }

                    RiskAssessment risk = RiskAssessment.builder()
                            .riskTitle(title)
                            .description(riskMap.getOrDefault("description", ""))
                            .service(service)
                            .severity(severity)
                            .category(riskMap.getOrDefault("category", "pattern_anomaly"))
                            .suggestedAction(riskMap.getOrDefault("suggestedAction", ""))
                            .status(RiskAssessment.Status.OPEN)
                            .build();

                    riskAssessmentRepository.save(risk);
                    newRisks.add(risk);
                    log.info("New risk identified: [{}] {} — {} (service: {})",
                            risk.getSeverity(), risk.getRiskTitle(), risk.getCategory(), risk.getService());
                }
            }
        } catch (Exception e) {
            log.error("LLM proactive analysis call failed: {}", e.getMessage(), e);
        }

        // 4. Broadcast new risks via WebSocket
        for (RiskAssessment risk : newRisks) {
            gatewayHandler.broadcast("risk.identified", Map.of(
                    "id", risk.getId(),
                    "title", risk.getRiskTitle(),
                    "severity", risk.getSeverity().name(),
                    "service", risk.getService() != null ? risk.getService() : "system-wide",
                    "category", risk.getCategory(),
                    "suggestedAction", risk.getSuggestedAction()
            ));
        }

        log.info("Proactive analysis complete. New risks identified: {}", newRisks.size());
        return newRisks;
    }

    /**
     * Build a comprehensive system snapshot for the LLM.
     */
    private String buildSystemSnapshot() {
        StringBuilder sb = new StringBuilder();

        // Monitored services
        List<MonitoredService> services = serviceRepository.findByEnabled(true);
        sb.append("## Monitored Services\n");
        if (services.isEmpty()) {
            sb.append("No services currently monitored.\n");
        } else {
            for (MonitoredService svc : services) {
                sb.append(String.format("- %s (type: %s, health: %s, consecutive_failures: %d, last_check: %s)\n",
                        svc.getName(), svc.getType(), svc.getHealthStatus(),
                        svc.getConsecutiveFailures(),
                        svc.getLastCheckAt() != null ? svc.getLastCheckAt().toString() : "never"));
            }
        }

        // Active incidents
        List<Incident> activeIncidents = incidentRepository.findActiveIncidents();
        sb.append("\n## Active Incidents\n");
        if (activeIncidents.isEmpty()) {
            sb.append("No active incidents.\n");
        } else {
            for (Incident inc : activeIncidents) {
                sb.append(String.format("- [%s] %s — %s (service: %s, since: %s)\n",
                        inc.getSeverity(), inc.getTitle(), inc.getStatus(),
                        inc.getService(), inc.getCreatedAt()));
            }
        }

        // Recent resolved incidents (last 24h) — for flapping detection
        Instant twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Incident> recentResolved = incidentRepository.findRecentIncidents(twentyFourHoursAgo).stream()
                .filter(i -> i.getStatus() == Incident.IncidentStatus.RESOLVED ||
                             i.getStatus() == Incident.IncidentStatus.CLOSED)
                .collect(Collectors.toList());
        sb.append("\n## Recent Resolved Incidents (24h)\n");
        if (recentResolved.isEmpty()) {
            sb.append("No recently resolved incidents.\n");
        } else {
            for (Incident inc : recentResolved) {
                long resTimeMs = (inc.getResolvedAt() != null && inc.getCreatedAt() != null)
                        ? inc.getResolvedAt().toEpochMilli() - inc.getCreatedAt().toEpochMilli() : 0;
                sb.append(String.format("- [%s] %s (service: %s, resolution_time: %ds)\n",
                        inc.getSeverity(), inc.getTitle(), inc.getService(), resTimeMs / 1000));
            }
        }

        // Resolution patterns / learning insights
        sb.append("\n## Resolution Insights\n");
        try {
            Map<String, Object> insights = learningService.getInsights();
            sb.append(String.format("Total resolutions: %s, Success rate: %s\n",
                    insights.getOrDefault("totalResolutions", 0),
                    insights.getOrDefault("overallSuccessRate", "N/A")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topPatterns = (List<Map<String, Object>>) insights.getOrDefault("topPatterns", List.of());
            for (Map<String, Object> pattern : topPatterns) {
                sb.append(String.format("  - Pattern: %s (count: %s)\n",
                        pattern.getOrDefault("tools", ""), pattern.getOrDefault("count", 0)));
            }
        } catch (Exception e) {
            sb.append("No learning data available.\n");
        }

        // Current metrics
        Map<String, Double> metrics = anomalyDetector.getCurrentMetrics();
        sb.append("\n## Current Metrics\n");
        if (metrics.isEmpty()) {
            sb.append("No custom metrics reported.\n");
        } else {
            metrics.forEach((k, v) -> sb.append(String.format("- %s = %.2f\n", k, v)));
        }

        return sb.toString();
    }

    /**
     * Build the LLM prompt for proactive risk analysis.
     */
    private String buildAnalysisPrompt(String snapshot) {
        return String.format("""
                You are an SRE analyzing system state to identify emerging risks before they become incidents.
                
                ## Current System State
                %s
                
                ## Task
                Identify 0-5 emerging risks, patterns, or concerning trends. Consider:
                - Service flapping (repeated up/down cycles)
                - Resource exhaustion patterns
                - Anomalous resolution times
                - Services with high consecutive failure counts
                - Correlation between incidents across services
                - Time-based patterns (e.g., issues recurring at similar times)
                
                For each risk, provide a JSON object with:
                - "title": concise risk title
                - "description": detailed explanation of the risk
                - "service": service name or null if system-wide
                - "severity": "CRITICAL", "HIGH", "MEDIUM", or "LOW"
                - "category": one of "flapping", "resource_exhaustion", "pattern_anomaly", "correlation", "degradation"
                - "suggestedAction": specific recommended remediation step
                
                Only report genuine risks. If the system looks healthy, return an empty array [].
                Respond ONLY with a JSON array.
                """, snapshot);
    }
}
