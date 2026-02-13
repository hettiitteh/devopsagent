package com.example.devopsagent.agent;

import com.example.devopsagent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dynamic System Prompt Builder (OpenClaw Architecture).
 *
 * Like OpenClaw's 17+ section system prompt, this assembles the prompt
 * dynamically based on context, available tools, and configuration.
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

        // Section 9: Time
        prompt.append(buildTimeSection());

        return prompt.toString();
    }

    private String buildIdentitySection() {
        return """
                # Identity
                You are an SRE (Site Reliability Engineering) Agent running inside the DevOps Agent platform.
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

                """;
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
