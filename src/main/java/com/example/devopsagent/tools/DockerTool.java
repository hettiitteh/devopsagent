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
 * Docker Tool - Manage Docker containers and inspect their state.
 */
@Slf4j
@Component
public class DockerTool implements AgentTool {

    @Override
    public String getName() { return "docker_exec"; }

    @Override
    public String getDescription() {
        return "Execute Docker commands to inspect and manage containers. " +
               "Supports ps, inspect, logs, stats, restart, and other container operations. " +
               "Use for investigating container issues, checking resource usage, and managing services.";
    }

    @Override
    public String getCategory() { return "infrastructure"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description",
                    "Docker subcommand (e.g., 'ps', 'inspect <id>', 'stats --no-stream', 'restart <id>')"),
                "format", Map.of("type", "string", "description", "Output format for applicable commands")
            ),
            "required", List.of("command")
        );
    }

    @Override
    public boolean isMutating() { return true; }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String command = (String) parameters.get("command");
        String fullCommand = "docker " + sanitize(command);

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
                    "Docker Result (exit code: %d):\nCommand: %s\n---\n%s",
                    exitCode, fullCommand, result));

        } catch (Exception e) {
            return ToolResult.error("Docker execution failed: " + e.getMessage());
        }
    }

    private String sanitize(String input) {
        return input.replaceAll("[;&|`$]", "");
    }
}
