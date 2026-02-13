package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SSH Execution Tool - Execute commands on remote servers via SSH.
 */
@Slf4j
@Component
public class SshExecTool implements AgentTool {

    @Override
    public String getName() { return "ssh_exec"; }

    @Override
    public String getDescription() {
        return "Execute commands on remote servers via SSH. " +
               "Supports key-based authentication. " +
               "Use for checking remote server health, gathering diagnostics, and executing remediation steps.";
    }

    @Override
    public String getCategory() { return "infrastructure"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public boolean isMutating() { return true; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "host", Map.of("type", "string", "description", "Remote host address"),
                "port", Map.of("type", "integer", "description", "SSH port", "default", 22),
                "username", Map.of("type", "string", "description", "SSH username"),
                "command", Map.of("type", "string", "description", "Command to execute on the remote server"),
                "key_path", Map.of("type", "string", "description", "Path to SSH private key"),
                "timeout_seconds", Map.of("type", "integer", "description", "Command timeout in seconds", "default", 30)
            ),
            "required", List.of("host", "username", "command")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String host = (String) parameters.get("host");
        int port = parameters.containsKey("port") ? ((Number) parameters.get("port")).intValue() : 22;
        String username = (String) parameters.get("username");
        String command = (String) parameters.get("command");
        String keyPath = (String) parameters.getOrDefault("key_path", System.getProperty("user.home") + "/.ssh/id_rsa");
        int timeout = parameters.containsKey("timeout_seconds")
                ? ((Number) parameters.get("timeout_seconds")).intValue() : 30;

        log.info("SSH executing on {}@{}:{} - {}", username, host, port, command);

        try {
            JSch jsch = new JSch();
            jsch.addIdentity(keyPath);

            Session session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(timeout * 1000);
            session.connect();

            try {
                ChannelExec channel = (ChannelExec) session.openChannel("exec");
                channel.setCommand(command);
                channel.setInputStream(null);
                channel.setErrStream(System.err);

                String output;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInputStream()))) {
                    channel.connect();
                    output = reader.lines().collect(Collectors.joining("\n"));
                }

                int exitCode = channel.getExitStatus();
                channel.disconnect();

                return ToolResult.text(String.format(
                        "SSH Result (exit code: %d):\nHost: %s@%s:%d\nCommand: %s\n---\n%s",
                        exitCode, username, host, port, command, output));
            } finally {
                session.disconnect();
            }
        } catch (Exception e) {
            return ToolResult.error(String.format(
                    "SSH execution failed on %s@%s:%d - %s", username, host, port, e.getMessage()));
        }
    }
}
