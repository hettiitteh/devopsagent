package com.example.devopsagent.agent;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.service.LearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Dynamic System Prompt Builder.
 *
 * Assembles the LLM system prompt dynamically based on context,
 * available tools, and configuration.
 *
 * Sections:
 * 1. Identity — SRE Agent persona
 * 2. Capabilities — What the agent can do
 * 3. Available Tools — Filtered by policy
 * 4. Safety — Operational guardrails
 * 5. Context — Current system state
 * 6. Memory — Past incidents and knowledge
 * 7. Playbooks — Available runbooks
 * 8. Monitoring — Current alerts and metrics
 * 9. Time — Current timestamp and timezone
 */
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final AgentProperties properties;
    private final LearningService learningService;

    /**
     * Build the full system prompt for the SRE agent.
     */
    public String buildSystemPrompt(List<AgentTool> tools, AgentContext context) {
        StringBuilder prompt = new StringBuilder();

        // Section 1: Identity
        prompt.append(buildIdentitySection());

        // Section 2: Capabilities
        prompt.append(buildCapabilitiesSection());

        // Section 3: Available Tools
        prompt.append(buildToolsSection(tools));

        // Section 4: Safety & Operational Guardrails
        prompt.append(buildSafetySection());

        // Section 5: Context
        if (context != null) {
            prompt.append(buildContextSection(context));
        }

        // Section 6: Monitoring Rules
        prompt.append(buildMonitoringSection());

        // Section 7: Incident Response Protocol
        prompt.append(buildIncidentProtocolSection());

        // Section 8: Playbook Guidelines
        prompt.append(buildPlaybookSection());

        // Section 8.5: Global Learning Insights (always included)
        prompt.append(buildGlobalLearningSection());

        // Section 8.6: Per-Service Recommended Approaches from Learning
        if (context != null && context.getAdditionalContext() != null) {
            prompt.append(buildRecommendedApproachesSection(context.getAdditionalContext()));
        }

        // Section 9: Time
        prompt.append(buildTimeSection());

        return prompt.toString();
    }

    private String buildIdentitySection() {
        return """
                # Identity
                You are Jarvis, an SRE (Site Reliability Engineering) Engineer.
                You are an autonomous agent that monitors production systems, detects issues early,
                runs playbooks, and mitigates problems. You act as a tireless SRE team member
                that is always watching, always ready to respond.

                Your primary objectives:
                1. Monitor production services and infrastructure health
                2. Detect anomalies and issues before they impact users
                3. Triage and classify incidents by severity
                4. Execute remediation playbooks when appropriate
                5. Escalate to humans when automated remediation is insufficient
                6. Maintain a knowledge base of past incidents for faster resolution

                """;
    }

    private String buildCapabilitiesSection() {
        return """
                # Capabilities
                You can perform the following types of operations:
                - **Health Checks**: Probe HTTP endpoints, TCP ports, Kubernetes pods, Docker containers
                - **Metrics**: Query Prometheus, CloudWatch, Datadog, and custom metrics
                - **Logs**: Search and analyze logs from various sources
                - **Kubernetes**: Execute kubectl commands, inspect pods/deployments/services
                - **Docker**: Manage containers, inspect logs, restart services
                - **SSH**: Execute commands on remote servers
                - **Playbooks**: Run automated remediation runbooks
                - **Incidents**: Create, update, and manage incidents
                - **Alerts**: Configure and manage alert rules
                - **Notifications**: Send alerts via Slack, PagerDuty, email

                """;
    }

    private String buildToolsSection(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "# Available Tools\nNo tools currently available.\n\n";
        }

        StringBuilder section = new StringBuilder("# Available Tools\n");
        section.append("You have access to the following tools. Use them to investigate and resolve issues:\n\n");

        for (AgentTool tool : tools) {
            section.append(String.format("- **%s** (%s): %s\n",
                    tool.getName(), tool.getCategory(), tool.getDescription()));
        }
        section.append("\n");
        return section.toString();
    }

    private String buildSafetySection() {
        return """
                # Safety & Operational Guardrails
                - NEVER execute destructive commands (rm -rf /, mkfs, etc.) without explicit approval
                - ALWAYS prefer read-only operations when investigating
                - ALWAYS check the impact of a remediation before executing
                - NEVER expose secrets, credentials, or sensitive data in logs or responses
                - ALWAYS follow the principle of least privilege
                - If unsure about the impact of an action, ESCALATE to a human
                - For critical severity incidents, ALWAYS notify the on-call team
                - Rate limit your own actions: don't retry failed operations more than 3 times
                - Document every action taken during incident response

                """;
    }

    private String buildContextSection(AgentContext context) {
        StringBuilder section = new StringBuilder("# Current Context\n");
        if (context.getActiveIncidents() > 0) {
            section.append(String.format("- Active incidents: %d\n", context.getActiveIncidents()));
        }
        if (context.getUnhealthyServices() > 0) {
            section.append(String.format("- Unhealthy services: %d\n", context.getUnhealthyServices()));
        }
        if (context.getActiveAlerts() > 0) {
            section.append(String.format("- Active alerts: %d\n", context.getActiveAlerts()));
        }
        if (context.getRunningPlaybooks() > 0) {
            section.append(String.format("- Running playbooks: %d\n", context.getRunningPlaybooks()));
        }
        section.append("\n");
        return section.toString();
    }

    private String buildMonitoringSection() {
        return """
                # Monitoring Rules
                When monitoring services:
                1. Check health endpoints at configured intervals
                2. Look for patterns: increasing error rates, rising latency, memory leaks
                3. Correlate issues across services (cascading failures)
                4. Track metrics over time to detect trends
                5. Use anomaly detection for unusual patterns
                6. Consider time-of-day patterns (traffic spikes, batch jobs)

                """;
    }

    private String buildIncidentProtocolSection() {
        return """
                # Incident Response Protocol
                When an incident is detected:
                1. **Detect**: Identify the issue from health checks, metrics, or alerts
                2. **Triage**: Classify severity (CRITICAL, HIGH, MEDIUM, LOW, INFO)
                3. **Notify**: Alert the appropriate channels based on severity
                4. **Investigate**: Gather logs, metrics, and system state
                5. **Mitigate**: Execute the appropriate playbook or manual steps
                6. **Verify**: Confirm the issue is resolved
                7. **Document**: Record root cause, actions taken, and lessons learned
                8. **Review**: Update playbooks and alert rules based on the incident

                Severity Classification:
                - CRITICAL: Service is down, data loss risk, user-facing impact
                - HIGH: Service degraded, significant performance impact
                - MEDIUM: Non-critical service issue, potential future impact
                - LOW: Minor issue, cosmetic, no immediate impact
                - INFO: Informational finding, no action needed

                """;
    }

    private String buildPlaybookSection() {
        return """
                # Playbook Guidelines
                When executing playbooks:
                1. Verify the playbook is appropriate for the current incident
                2. Check if approval is required before execution
                3. Execute steps sequentially unless parallel execution is specified
                4. Log the output of each step
                5. If a step fails, decide whether to continue, retry, or abort
                6. Verify the outcome after completion
                7. Update the incident with the playbook results

                # Playbook Authoring Standards
                When CREATING new playbooks (via playbook_create), follow these mandatory standards:

                ## Standard Step Pattern
                Every remediation playbook MUST follow this 4-step pattern:
                1. **Verify the Problem** — Use health_check or docker_exec (action: inspect) to confirm the service is actually down. Set onFailure: continue.
                2. **Collect Diagnostics** — Use log_search, network_diag, or system_info to gather context before taking action. Set onFailure: continue.
                3. **Remediate** — Use service_restart, docker_exec, or kubectl_exec to fix the issue. Set onFailure: abort.
                4. **Verify Recovery** — Use health_check with retries to confirm the fix worked. Set onFailure: retry, maxRetries: 3, retryDelaySeconds: 5.

                ## Parameter Rules
                - NEVER leave step parameters empty (`{}`). Every step MUST have fully specified parameters.
                - For service_restart: ALWAYS include service_type ("docker", "kubernetes", or "systemd"), service_name, and graceful (true/false).
                - For health_check: ALWAYS include url (the service's health endpoint).
                - For docker_exec: ALWAYS include action and container name.
                - For log_search: ALWAYS include source and target.
                - For network_diag: ALWAYS include command and target.
                - Use `${service_name}` placeholder syntax when the value should come from the trigger context.

                ## Trigger Rules
                - Always set trigger_service to the specific service name this playbook is for.
                - Set trigger_severity to the appropriate levels (e.g., "HIGH", "CRITICAL").

                ## Failure Handling
                - Diagnostic/verification steps: onFailure = "continue" (don't abort if diagnostics fail)
                - Remediation steps: onFailure = "abort" (stop if the fix itself fails)
                - Recovery verification steps: onFailure = "retry" with maxRetries: 3 and retryDelaySeconds: 5

                ## Example: A Well-Structured Docker Service Restart Playbook
                ```
                Steps:
                  1. "Verify service is down" — tool: health_check, params: {url: "http://localhost:<port>"}, onFailure: continue
                  2. "Inspect container state" — tool: docker_exec, params: {action: "inspect", container: "<service>"}, onFailure: continue
                  3. "Restart the service" — tool: service_restart, params: {service_type: "docker", service_name: "<service>", graceful: true}, onFailure: abort
                  4. "Verify recovery" — tool: health_check, params: {url: "http://localhost:<port>"}, onFailure: retry, maxRetries: 3, retryDelaySeconds: 5
                ```

                """;
    }

    @SuppressWarnings("unchecked")
    private String buildGlobalLearningSection() {
        try {
            Map<String, Object> insights = learningService.getInsights();
            int totalResolutions = ((Number) insights.getOrDefault("total_resolutions", 0)).intValue();
            if (totalResolutions == 0) return "";

            StringBuilder section = new StringBuilder("# Learning Insights (from past resolutions)\n");

            // Overall stats
            double overallSuccessRate = 0;
            Object successRateObj = insights.get("overall_success_rate");
            if (successRateObj instanceof Number) {
                overallSuccessRate = ((Number) successRateObj).doubleValue();
            }
            section.append(String.format("Total resolutions recorded: %d (overall success rate: %.1f%%)\n\n", totalResolutions, overallSuccessRate));

            // Top resolution patterns
            Object patternsObj = insights.get("patterns");
            if (patternsObj instanceof List<?>) {
                List<Map<String, Object>> patterns = (List<Map<String, Object>>) patternsObj;
                if (!patterns.isEmpty()) {
                    section.append("Top resolution patterns:\n");
                    int count = 0;
                    for (Map<String, Object> p : patterns) {
                        if (count >= 3) break;
                        section.append(String.format("  - Tools: %s (used %s times)\n",
                                p.getOrDefault("sequence", "unknown"),
                                p.getOrDefault("count", 0)));
                        count++;
                    }
                    section.append("\n");
                }
            }

            // Per-service stats
            Object serviceStatsObj = insights.get("service_stats");
            if (serviceStatsObj instanceof List<?>) {
                List<Map<String, Object>> serviceStats = (List<Map<String, Object>>) serviceStatsObj;
                if (!serviceStats.isEmpty()) {
                    section.append("Per-service success rates:\n");
                    for (Map<String, Object> s : serviceStats) {
                        section.append(String.format("  - %s: %.1f%% success (%s resolutions)\n",
                                s.getOrDefault("service", "unknown"),
                                s.get("success_rate") instanceof Number ? ((Number) s.get("success_rate")).doubleValue() : 0.0,
                                s.getOrDefault("total", 0)));
                    }
                    section.append("\n");
                }
            }

            section.append("Use these patterns to inform your approach. Prefer proven tool sequences when applicable.\n\n");
            return section.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildRecommendedApproachesSection(String serviceContext) {
        StringBuilder section = new StringBuilder();

        // Exact-match recommendation (by service name)
        try {
            String recommendation = learningService.getRecommendedApproach(serviceContext);
            if (recommendation != null && !recommendation.isEmpty()) {
                double successRate = learningService.getSuccessRate(serviceContext);
                section.append(String.format("""
                    # Recommended Approaches (from past resolutions)
                    %s
                    Success rate for this service: %.1f%%

                    """, recommendation, successRate));
            }
        } catch (Exception e) {
            // ignore
        }

        // Semantic recommendation (cross-service, embedding-based)
        try {
            String semanticRec = learningService.getSemanticRecommendation(serviceContext);
            if (semanticRec != null && !semanticRec.isEmpty()) {
                section.append("# Cross-Service Semantic Insights\n");
                section.append(semanticRec);
                section.append("Consider these approaches even if from different services — the problems are semantically similar.\n\n");
            }
        } catch (Exception e) {
            // ignore
        }

        return section.toString();
    }

    private String buildTimeSection() {
        String now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .format(Instant.now().atZone(ZoneId.systemDefault()));
        return String.format("# Time\nCurrent time: %s\n\n", now);
    }

    /**
     * Context data passed to the prompt builder.
     */
    @lombok.Data
    @lombok.Builder
    public static class AgentContext {
        @lombok.Builder.Default
        private int activeIncidents = 0;
        @lombok.Builder.Default
        private int unhealthyServices = 0;
        @lombok.Builder.Default
        private int activeAlerts = 0;
        @lombok.Builder.Default
        private int runningPlaybooks = 0;
        private String currentIncidentId;
        private String additionalContext;
    }
}
