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
 * Network Diagnostics Tool - Run network diagnostic commands.
 */
@Slf4j
@Component
public class NetworkDiagTool implements AgentTool {

    @Override
    public String getName() { return "network_diag"; }

    @Override
    public String getDescription() {
        return "Run network diagnostic commands: ping, traceroute, dig, nslookup, curl, netstat/ss. " +
               "Use for investigating connectivity issues, DNS problems, and network path analysis.";
    }

    @Override
    public String getCategory() { return "diagnostics"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "tool", Map.of("type", "string", "description",
                    "Diagnostic tool: ping, traceroute, dig, nslookup, curl, netstat, ss"),
                "target", Map.of("type", "string", "description",
                    "Target host, IP, or URL"),
                "options", Map.of("type", "string", "description",
                    "Additional command options (e.g., '-c 5' for ping count)")
            ),
            "required", List.of("tool", "target")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String tool = sanitize((String) parameters.get("tool"));
        String target = sanitize((String) parameters.get("target"));
        String options = sanitize((String) parameters.getOrDefault("options", ""));

        // Validate tool is allowed
        List<String> allowedTools = List.of("ping", "traceroute", "dig", "nslookup", "curl", "netstat", "ss");
        if (!allowedTools.contains(tool.toLowerCase())) {
            return ToolResult.error("Tool not allowed: " + tool + ". Allowed: " + allowedTools);
        }

        String command = switch (tool.toLowerCase()) {
            case "ping" -> "ping -c 4 " + options + " " + target;
            case "traceroute" -> "traceroute " + options + " " + target;
            case "dig" -> "dig " + options + " " + target;
            case "nslookup" -> "nslookup " + options + " " + target;
            case "curl" -> "curl -sS -o /dev/null -w '%{http_code} %{time_total}s %{size_download}B' " + options + " " + target;
            case "netstat" -> "netstat -tuln " + options;
            case "ss" -> "ss -tuln " + options;
            default -> throw new IllegalArgumentException("Unexpected tool: " + tool);
        };

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
                    "Network Diagnostics (%s):\nCommand: %s\nExit Code: %d\n---\n%s",
                    tool, command, exitCode, output));

        } catch (Exception e) {
            return ToolResult.error("Network diagnostic failed: " + e.getMessage());
        }
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[;&|`$]", "");
    }
}
