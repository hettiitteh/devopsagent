package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentEngine;
import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-generates playbooks from successful agent chat tool sequences.
 * When an agent resolves an incident using multiple tools, this service
 * can capture that sequence and save it as a reusable playbook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybookGeneratorService {

    private final AgentEngine agentEngine;
    private final PlaybookEngine playbookEngine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Generate a playbook from an agent session's tool call history.
     */
    public Playbook generateFromSession(String sessionId, String playbookName, String description) {
        Optional<AgentEngine.AgentSession> sessionOpt = agentEngine.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        AgentEngine.AgentSession session = sessionOpt.get();
        List<AgentMessage> messages = session.getMessages();

        // Extract tool calls from the conversation
        List<Playbook.Step> steps = new ArrayList<>();
        AtomicInteger order = new AtomicInteger(1);

        for (AgentMessage msg : messages) {
            if (msg.getToolCalls() != null) {
                for (AgentMessage.ToolCall tc : msg.getToolCalls()) {
                    steps.add(Playbook.Step.builder()
                            .order(order.getAndIncrement())
                            .name(tc.getName().replace("_", " "))
                            .tool(tc.getName())
                            .parameters(tc.getArguments() != null ? new HashMap<>(tc.getArguments()) : Map.of())
                            .onFailure("continue")
                            .build());
                }
            }
        }

        if (steps.isEmpty()) {
            throw new IllegalStateException("No tool calls found in session " + sessionId);
        }

        String id = playbookName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");

        Playbook playbook = Playbook.builder()
                .id(id)
                .name(playbookName)
                .description(description != null ? description : "Auto-generated from agent session " + sessionId)
                .version("1.0")
                .author("auto-generated")
                .approvalRequired(false)
                .maxExecutionTimeSeconds(300)
                .tags(List.of("auto-generated", "agent-session"))
                .steps(steps)
                .build();

        log.info("Generated playbook '{}' with {} steps from session {}",
                playbookName, steps.size(), sessionId);

        return playbook;
    }

    /**
     * Generate and save a playbook to disk, then reload the engine.
     */
    public Playbook generateAndSave(String sessionId, String playbookName, String description) {
        Playbook playbook = generateFromSession(sessionId, playbookName, description);

        try {
            String directory = "./playbooks";
            File dir = new File(directory);
            if (!dir.exists()) dir.mkdirs();

            String filename = playbook.getId() + ".yml";
            File file = new File(dir, filename);
            yamlMapper.writeValue(file, playbook);

            log.info("Saved auto-generated playbook to {}", file.getAbsolutePath());

            // Reload playbooks so the new one is available
            playbookEngine.loadPlaybooks();

            // Audit
            auditService.log("system", "PLAYBOOK_GENERATED", playbook.getId(),
                    Map.of("name", playbookName, "steps", playbook.getSteps().size(),
                           "session_id", sessionId));

            return playbook;
        } catch (IOException e) {
            log.error("Failed to save generated playbook: {}", e.getMessage());
            throw new RuntimeException("Failed to save playbook: " + e.getMessage(), e);
        }
    }
}
