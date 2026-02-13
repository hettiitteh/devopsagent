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
 * Log Search Tool - Search and analyze logs from various sources.
 * Supports local log files, journalctl, kubectl logs, and docker logs.
 */
@Slf4j
@Component
public class LogSearchTool implements AgentTool {

    @Override
    public String getName() { return "log_search"; }

    @Override
    public String getDescription() {
        return "Search and analyze logs from various sources: local files, journalctl, " +
               "kubectl logs, or docker logs. Supports grep patterns, tail, and time-based filtering. " +
               "Use for debugging errors, tracing request flows, and identifying patterns.";
    }

    @Override
    public String getCategory() { return "monitoring"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "source", Map.of("type", "string", "description", "Log source: file, journalctl, kubectl, docker"),
                "target", Map.of("type", "string", "description", "Target: file path, service name, pod name, or container ID"),
                "pattern", Map.of("type", "string", "description", "Search pattern (grep regex)"),
                "lines", Map.of("type", "integer", "description", "Number of lines to return", "default", 100),
                "since", Map.of("type", "string", "description", "Time filter (e.g., '1h', '30m', '2024-01-01')"),
                "container", Map.of("type", "string", "description", "Container name (for kubectl/docker)")
            ),
            "required", List.of("source", "target")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String source = (String) parameters.get("source");
        String target = (String) parameters.get("target");
        String pattern = (String) parameters.getOrDefault("pattern", "");
        int lines = parameters.containsKey("lines") ? ((Number) parameters.get("lines")).intValue() : 100;
        String since = (String) parameters.getOrDefault("since", "");

        try {
            String command = buildCommand(source, target, pattern, lines, since, parameters);
            return executeCommand(command);
        } catch (Exception e) {
            return ToolResult.error("Log search failed: " + e.getMessage());
        }
    }

    private String buildCommand(String source, String target, String pattern,
                                 int lines, String since, Map<String, Object> params) {
        return switch (source.toLowerCase()) {
            case "file" -> {
                String cmd = "tail -n " + lines + " " + target;
                if (!pattern.isEmpty()) {
                    cmd += " | grep -E '" + sanitize(pattern) + "'";
                }
                yield cmd;
            }
            case "journalctl" -> {
                String cmd = "journalctl -u " + sanitize(target) + " -n " + lines + " --no-pager";
                if (!since.isEmpty()) cmd += " --since '" + sanitize(since) + "'";
                if (!pattern.isEmpty()) cmd += " | grep -E '" + sanitize(pattern) + "'";
                yield cmd;
            }
            case "kubectl" -> {
                String cmd = "kubectl logs " + sanitize(target);
                String container = (String) params.getOrDefault("container", "");
                if (!container.isEmpty()) cmd += " -c " + sanitize(container);
                cmd += " --tail=" + lines;
                if (!since.isEmpty()) cmd += " --since=" + sanitize(since);
                if (!pattern.isEmpty()) cmd += " | grep -E '" + sanitize(pattern) + "'";
                yield cmd;
            }
            case "docker" -> {
                String cmd = "docker logs " + sanitize(target) + " --tail " + lines;
                if (!since.isEmpty()) cmd += " --since " + sanitize(since);
                if (!pattern.isEmpty()) cmd += " 2>&1 | grep -E '" + sanitize(pattern) + "'";
                yield cmd;
            }
            default -> throw new IllegalArgumentException("Unknown log source: " + source);
        };
    }

    private ToolResult executeCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            return ToolResult.text(String.format(
                    "Log Search Results (exit code: %d):\nCommand: %s\n---\n%s",
                    exitCode, command, output.isEmpty() ? "(no matching logs found)" : output));

        } catch (Exception e) {
            return ToolResult.error("Failed to execute log search: " + e.getMessage());
        }
    }

    private String sanitize(String input) {
        // Basic sanitization to prevent command injection
        return input.replaceAll("[;&|`$]", "");
    }
}
