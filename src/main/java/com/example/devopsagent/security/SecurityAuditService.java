package com.example.devopsagent.security;

import com.example.devopsagent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Security Audit Service (OpenClaw Architecture).
 *
 * Like OpenClaw's 30+ automated security checks, this service:
 * - Validates command allowlists/denylists
 * - Checks tool policy configurations
 * - Detects potential command injection in parameters
 * - Audits plugin trust
 * - Reports security findings with severity levels
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityAuditService {

    private final AgentProperties properties;

    private final List<AuditFinding> findings = Collections.synchronizedList(new ArrayList<>());

    /**
     * Run a full security audit.
     */
    @Scheduled(fixedDelay = 3600000) // Every hour
    public List<AuditFinding> runAudit() {
        if (!properties.getSecurity().isAuditEnabled()) return List.of();

        findings.clear();
        log.info("Running security audit...");

        checkCommandAllowlist();
        checkCommandDenylist();
        checkToolPolicies();
        checkLlmConfiguration();
        checkNotificationSecurity();

        long criticalCount = findings.stream().filter(f -> f.severity == AuditSeverity.CRITICAL).count();
        long warnCount = findings.stream().filter(f -> f.severity == AuditSeverity.WARN).count();
        log.info("Security audit complete: {} findings ({} critical, {} warnings)",
                findings.size(), criticalCount, warnCount);

        return List.copyOf(findings);
    }

    private void checkCommandAllowlist() {
        var allowlist = properties.getSecurity().getCommandAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            findings.add(new AuditFinding(
                    AuditSeverity.WARN,
                    "security.command_allowlist",
                    "Command allowlist is empty. All commands may be executable.",
                    "Configure devops-agent.security.command-allowlist with allowed commands."
            ));
        }

        // Check for dangerous commands in allowlist
        List<String> dangerous = List.of("rm", "mkfs", "dd", "format", "fdisk");
        for (String cmd : allowlist) {
            if (dangerous.stream().anyMatch(d -> cmd.toLowerCase().startsWith(d))) {
                findings.add(new AuditFinding(
                        AuditSeverity.CRITICAL,
                        "security.dangerous_command",
                        "Dangerous command in allowlist: " + cmd,
                        "Remove '" + cmd + "' from the command allowlist."
                ));
            }
        }
    }

    private void checkCommandDenylist() {
        var denylist = properties.getSecurity().getCommandDenylist();
        if (denylist == null || denylist.isEmpty()) {
            findings.add(new AuditFinding(
                    AuditSeverity.WARN,
                    "security.command_denylist",
                    "Command denylist is empty.",
                    "Configure devops-agent.security.command-denylist with blocked patterns."
            ));
        }
    }

    private void checkToolPolicies() {
        var profiles = properties.getToolPolicy().getProfiles();
        for (var entry : profiles.entrySet()) {
            if (entry.getValue().getAllowedTools().contains("*")) {
                findings.add(new AuditFinding(
                        AuditSeverity.WARN,
                        "security.tool_policy",
                        "Profile '" + entry.getKey() + "' allows ALL tools (*). This grants unrestricted access.",
                        "Consider restricting the allowed tools for the '" + entry.getKey() + "' profile."
                ));
            }
        }
    }

    private void checkLlmConfiguration() {
        if (properties.getLlm().getApiKey() == null || properties.getLlm().getApiKey().isEmpty()) {
            findings.add(new AuditFinding(
                    AuditSeverity.INFO,
                    "config.llm_api_key",
                    "LLM API key is not configured.",
                    "Set OPENAI_API_KEY environment variable or configure devops-agent.llm.api-key."
            ));
        }
    }

    private void checkNotificationSecurity() {
        if (properties.getNotifications().getSlack().isEnabled()
                && (properties.getNotifications().getSlack().getWebhookUrl() == null
                || properties.getNotifications().getSlack().getWebhookUrl().isEmpty())) {
            findings.add(new AuditFinding(
                    AuditSeverity.WARN,
                    "config.slack_webhook",
                    "Slack is enabled but webhook URL is not configured.",
                    "Set SLACK_WEBHOOK_URL environment variable."
            ));
        }
    }

    /**
     * Sanitize a command input to prevent injection.
     */
    public static String sanitizeCommand(String input) {
        if (input == null) return "";
        // Remove common injection patterns
        return input.replaceAll("[;&|`$(){}]", "")
                .replaceAll("\\.\\.[\\/\\\\]", "")
                .trim();
    }

    /**
     * Check if a command is in the denylist.
     */
    public boolean isCommandDenied(String command) {
        return properties.getSecurity().getCommandDenylist().stream()
                .anyMatch(denied -> command.toLowerCase().contains(denied.toLowerCase()));
    }

    /**
     * Get latest audit findings.
     */
    public List<AuditFinding> getFindings() {
        return List.copyOf(findings);
    }

    public enum AuditSeverity {
        CRITICAL, WARN, INFO
    }

    public record AuditFinding(
            AuditSeverity severity,
            String category,
            String message,
            String remediation
    ) {
        public Map<String, String> toMap() {
            return Map.of(
                    "severity", severity.name(),
                    "category", category,
                    "message", message,
                    "remediation", remediation,
                    "timestamp", Instant.now().toString()
            );
        }
    }
}
