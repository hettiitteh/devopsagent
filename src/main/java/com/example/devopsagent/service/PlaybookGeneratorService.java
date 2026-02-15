package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentEngine;
import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.domain.PlaybookDefinition;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.repository.PlaybookDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final PlaybookDefinitionRepository definitionRepository;
    private final AuditService auditService;

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
        return savePlaybook(playbook, Map.of("session_id", sessionId));
    }

    /**
     * Save a playbook to the database and reload the engine cache.
     * Used by the UI create form, the chat agent tool, and the session-based generator.
     */
    public Playbook savePlaybook(Playbook playbook, Map<String, Object> extraAuditData) {
        // Ensure the playbook has an ID
        if (playbook.getId() == null || playbook.getId().isBlank()) {
            String id = playbook.getName().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            playbook.setId(id);
        }
        if (playbook.getVersion() == null) playbook.setVersion("1.0");
        if (playbook.getAuthor() == null) playbook.setAuthor("user");

        // Ensure step ordering
        if (playbook.getSteps() != null) {
            for (int i = 0; i < playbook.getSteps().size(); i++) {
                playbook.getSteps().get(i).setOrder(i + 1);
            }
        }

        // Determine source from audit data, default to "ui"
        String source = "ui";
        if (extraAuditData != null && extraAuditData.containsKey("source")) {
            source = extraAuditData.get("source").toString();
        }

        PlaybookDefinition def = PlaybookDefinition.fromPlaybook(playbook, source);
        def.setEnabled(true);
        definitionRepository.save(def);

        log.info("Saved playbook '{}' to database (id: {})", playbook.getName(), def.getId());

        // Reload playbooks so the new one is available in the cache
        playbookEngine.loadPlaybooks();

        // Audit
        Map<String, Object> auditData = new HashMap<>(Map.of(
                "name", playbook.getName(),
                "steps", playbook.getSteps() != null ? playbook.getSteps().size() : 0));
        if (extraAuditData != null) auditData.putAll(extraAuditData);
        auditService.log("system", "PLAYBOOK_SAVED", playbook.getId(), auditData);

        return playbook;
    }

    /**
     * Delete a playbook from the database and reload the engine cache.
     */
    public boolean deletePlaybook(String playbookId) {
        if (!definitionRepository.existsById(playbookId)) {
            log.warn("Playbook not found in database for deletion: {}", playbookId);
            return false;
        }

        definitionRepository.deleteById(playbookId);
        log.info("Deleted playbook from database: {}", playbookId);
        playbookEngine.loadPlaybooks();
        auditService.log("system", "PLAYBOOK_DELETED", playbookId, Map.of());
        return true;
    }
}
