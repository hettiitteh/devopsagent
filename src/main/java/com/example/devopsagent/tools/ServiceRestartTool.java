package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service Restart Tool - Restart services via systemctl, kubectl, or docker.
 */
@Slf4j
@Component
public class ServiceRestartTool implements AgentTool {

    @Override
    public String getName() { return "service_restart"; }

    @Override
    public String getDescription() {
        return "Restart a service using systemctl, kubectl rollout restart, or docker restart. " +
               "Use as a remediation step when a service is unhealthy and needs to be recycled. " +
               "Always verify health after restart.";
    }

    @Override
    public String getCategory() { return "remediation"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public boolean isMutating() { return true; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "service_type", Map.of("type", "string", "description",
                    "Service type: systemd, kubernetes, docker"),
                "service_name", Map.of("type", "string", "description",
                    "Service name, deployment name, or container name/id"),
                "namespace", Map.of("type", "string", "description",
                    "Kubernetes namespace (for kubernetes type)", "default", "default"),
                "graceful", Map.of("type", "boolean", "description",
                    "Whether to perform a graceful restart", "default", true)
            ),
            "required", List.of("service_type", "service_name")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String serviceType = (String) parameters.get("service_type");
        String serviceName = sanitize((String) parameters.get("service_name"));
        String namespace = sanitize((String) parameters.getOrDefault("namespace", "default"));
        boolean graceful = parameters.containsKey("graceful") ? (boolean) parameters.get("graceful") : true;

        String command = switch (serviceType.toLowerCase()) {
            case "systemd" -> graceful
                    ? "sudo systemctl restart " + serviceName
                    : "sudo systemctl kill -s SIGKILL " + serviceName + " && sudo systemctl start " + serviceName;
            case "kubernetes" -> "kubectl rollout restart deployment/" + serviceName + " -n " + namespace;
            case "docker" -> graceful
                    ? "docker restart " + serviceName
                    : "docker kill " + serviceName + " && docker start " + serviceName;
            default -> throw new IllegalArgumentException("Unknown service type: " + serviceType);
        };

        log.info("Restarting service: {} (type: {}, graceful: {})", serviceName, serviceType, graceful);

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            String status = exitCode == 0 ? "SUCCESS" : "FAILED";

            return ToolResult.text(String.format(
                    "Service Restart %s:\nService: %s (%s)\nGraceful: %s\nCommand: %s\nExit Code: %d\n---\n%s",
                    status, serviceName, serviceType, graceful, command, exitCode, output));

        } catch (Exception e) {
            return ToolResult.error("Service restart failed: " + e.getMessage());
        }
    }

    private String sanitize(String input) {
        return input.replaceAll("[;&|`$]", "");
    }
}
