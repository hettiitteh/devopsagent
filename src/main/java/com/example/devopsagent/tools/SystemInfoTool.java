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
 * System Info Tool - Gather system information and resource usage.
 */
@Slf4j
@Component
public class SystemInfoTool implements AgentTool {

    @Override
    public String getName() { return "system_info"; }

    @Override
    public String getDescription() {
        return "Gather system information: CPU usage (top/htop), memory (free), disk (df/du), " +
               "uptime, processes, and system load. Use for capacity investigation and resource diagnostics.";
    }

    @Override
    public String getCategory() { return "diagnostics"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description",
                    "System command: top, free, df, du, uptime, ps, lsof, iostat, vmstat"),
                "options", Map.of("type", "string", "description",
                    "Additional options for the command")
            ),
            "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String command = sanitize((String) parameters.get("command"));
        String options = sanitize((String) parameters.getOrDefault("options", ""));

        List<String> allowed = List.of("top", "free", "df", "du", "uptime", "ps", "lsof", "iostat", "vmstat", "htop");
        if (!allowed.contains(command.toLowerCase())) {
            return ToolResult.error("Command not allowed: " + command);
        }

        String fullCommand = switch (command.toLowerCase()) {
            case "top" -> "top -b -n 1 " + options + " | head -30";
            case "free" -> "free -h " + options;
            case "df" -> "df -h " + options;
            case "du" -> "du -sh " + options;
            case "uptime" -> "uptime";
            case "ps" -> "ps aux " + options + " | head -30";
            case "lsof" -> "lsof " + options + " | head -30";
            case "iostat" -> "iostat " + options;
            case "vmstat" -> "vmstat " + options;
            default -> command + " " + options;
        };

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", fullCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            return ToolResult.text(String.format(
                    "System Info (%s):\n---\n%s", command, output));

        } catch (Exception e) {
            return ToolResult.error("System info command failed: " + e.getMessage());
        }
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[;&|`$]", "");
    }
}
