package com.example.devopsagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central configuration for the DevOps Agent.
 * Maps to the 'devops-agent' prefix in application.yml.
 * Supports hot-reload via Spring Cloud Config or @RefreshScope.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "devops-agent")
public class AgentProperties {

    private GatewayConfig gateway = new GatewayConfig();
    private LlmConfig llm = new LlmConfig();
    private MonitoringConfig monitoring = new MonitoringConfig();
    private PlaybookConfig playbooks = new PlaybookConfig();
    private IncidentConfig incidents = new IncidentConfig();
    private ToolPolicyConfig toolPolicy = new ToolPolicyConfig();
    private NotificationConfig notifications = new NotificationConfig();
    private SecurityConfig security = new SecurityConfig();
    private SuggestionsConfig suggestions = new SuggestionsConfig();

    @Data
    public static class GatewayConfig {
        private String websocketPath = "/ws/gateway";
        private int heartbeatIntervalSeconds = 30;
        private int maxSessions = 100;
    }

    @Data
    public static class LlmConfig {
        private String provider = "openai";
        private String model = "gpt-4o";
        private String apiKey = "";
        private double temperature = 0.1;
        private int maxTokens = 4096;
        private int timeoutSeconds = 120;
    }

    @Data
    public static class MonitoringConfig {
        private boolean enabled = true;
        private int pollIntervalSeconds = 30;
        private int healthCheckIntervalSeconds = 60;
        private int metricRetentionHours = 168;
        private boolean anomalyDetectionEnabled = true;
        private int alertCooldownMinutes = 5;
    }

    @Data
    public static class PlaybookConfig {
        private String directory = "./playbooks";
        private boolean autoExecute = false;
        private int maxExecutionTimeSeconds = 300;
        private boolean approvalRequired = true;
    }

    @Data
    public static class IncidentConfig {
        private boolean autoCreate = true;
        private boolean autoAssign = true;
        private int escalationTimeoutMinutes = 15;
        private int maxRetries = 3;
        private List<EscalationTier> escalationTiers = new ArrayList<>();

        @Data
        public static class EscalationTier {
            private String channel = "slack";
            private String label = "On-call team";
        }
    }

    @Data
    public static class ToolPolicyConfig {
        private String defaultProfile = "sre";
        private Map<String, ToolProfile> profiles = new HashMap<>();
        /** Tools that require human approval before execution (runtime-toggleable) */
        private List<String> approvalRequired = new ArrayList<>();

        @Data
        public static class ToolProfile {
            private List<String> allowedTools = new ArrayList<>();
        }
    }

    @Data
    public static class NotificationConfig {
        private SlackConfig slack = new SlackConfig();
        private PagerDutyConfig pagerduty = new PagerDutyConfig();
        private EmailConfig email = new EmailConfig();

        @Data
        public static class SlackConfig {
            private boolean enabled = false;
            private String webhookUrl = "";
        }

        @Data
        public static class PagerDutyConfig {
            private boolean enabled = false;
            private String apiKey = "";
        }

        @Data
        public static class EmailConfig {
            private boolean enabled = false;
            private String smtpHost = "localhost";
            private int smtpPort = 587;
        }
    }

    @Data
    public static class SuggestionsConfig {
        private boolean aiEnabled = true;
    }

    @Data
    public static class SecurityConfig {
        private boolean auditEnabled = true;
        private List<String> commandAllowlist = new ArrayList<>();
        private List<String> commandDenylist = new ArrayList<>();
    }
}
