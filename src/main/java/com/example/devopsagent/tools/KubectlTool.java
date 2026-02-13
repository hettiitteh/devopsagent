package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kubectl Tool - Execute kubectl commands for Kubernetes management.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KubectlTool implements AgentTool {

    private final AgentProperties properties;

    @Override
    public String getName() { return "kubectl_exec"; }

    @Override
    public String getDescription() {
        return "Execute kubectl commands to inspect and manage Kubernetes resources. " +
               "Supports get, describe, logs, top, rollout, scale, and other read/management operations. " +
               "Use for investigating pod issues, checking deployments, and managing workloads.";
    }

    @Override
    public String getCategory() { return "infrastructure"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description",
                    "kubectl subcommand (e.g., 'get pods', 'describe pod my-pod', 'top nodes')"),
                "namespace", Map.of("type", "string", "description", "Kubernetes namespace", "default", "default"),
                "context", Map.of("type", "string", "description", "Kubernetes context to use"),
                "output", Map.of("type", "string", "description", "Output format: json, yaml, wide, name", "default", "wide")
            ),
            "required", List.of("command")
        );
    }

    @Override
    public boolean isMutating() {
        return true; // Some kubectl commands can modify state
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String command = (String) parameters.get("command");
        String namespace = (String) parameters.getOrDefault("namespace", "default");
        String k8sContext = (String) parameters.getOrDefault("context", "");
        String output = (String) parameters.getOrDefault("output", "wide");

        // Security check: ensure kubectl is in the allowlist
        if (!properties.getSecurity().getCommandAllowlist().contains("kubectl")) {
            return ToolResult.error("kubectl is not in the command allowlist");
        }

        // Build the full command
        StringBuilder cmd = new StringBuilder("kubectl ");
        cmd.append(sanitize(command));
        cmd.append(" -n ").append(sanitize(namespace));

        if (!k8sContext.isEmpty()) {
            cmd.append(" --context=").append(sanitize(k8sContext));
        }

        // Add output format for applicable commands
        if (command.startsWith("get") || command.startsWith("describe")) {
            if (!command.contains("-o ")) {
                cmd.append(" -o ").append(sanitize(output));
            }
        }

        String fullCommand = cmd.toString();
        log.info("Executing: {}", fullCommand);

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", fullCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                result = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            return ToolResult.text(String.format(
                    "kubectl Result (exit code: %d):\nCommand: %s\n---\n%s",
                    exitCode, fullCommand, result));

        } catch (Exception e) {
            return ToolResult.error("kubectl execution failed: " + e.getMessage());
        }
    }

    private String sanitize(String input) {
        return input.replaceAll("[;&|`$]", "");
    }
}
