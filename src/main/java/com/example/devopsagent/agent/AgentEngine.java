package com.example.devopsagent.agent;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.service.AuditService;
import com.example.devopsagent.service.ApprovalService;
import com.example.devopsagent.service.LearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core Agent Engine - The Agentic Loop (OpenClaw Architecture).
 *
 * Like OpenClaw's Pi Agent framework, this implements the fundamental agentic loop:
 * 1. Build system prompt (dynamic sections based on context)
 * 2. Create tool set (filtered by 9-layer policy)
 * 3. Send prompt + tool definitions to LLM
 * 4. Receive response (text or tool calls)
 * 5. Execute tool calls (with policy checks)
 * 6. Append results to conversation
 * 7. Continue until task completion or max iterations
 *
 * There is no explicit task planner—the LLM itself drives the workflow
 * through reasoning, just like OpenClaw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEngine {

    private final LlmClient llmClient;
    private final ToolPolicyEngine toolPolicy;
    private final ToolRegistry toolRegistry;
    private final SystemPromptBuilder systemPromptBuilder;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final ApprovalService approvalService;
    private final LearningService learningService;

    private static final int MAX_ITERATIONS = 25;
    private static final int MAX_CONVERSATION_TOKENS = 128000;

    // Active agent sessions and their tool contexts
    private final Map<String, AgentSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, ToolContext> sessionToolContexts = new ConcurrentHashMap<>();

    /**
     * Run the agent with a user message, executing the full agentic loop.
     */
    @Async("agentExecutor")
    public CompletableFuture<AgentResponse> run(String sessionId, String userMessage,
                                                 SystemPromptBuilder.AgentContext context) {
        log.info("Agent session {} started: {}", sessionId, truncate(userMessage, 100));

        AgentSession session = activeSessions.computeIfAbsent(sessionId, id ->
                AgentSession.builder()
                        .sessionId(id)
                        .startedAt(Instant.now())
                        .messages(new ArrayList<>())
                        .build());

        try {
            // Step 1: Build system prompt
            String toolProfile = properties.getToolPolicy().getDefaultProfile();
            Set<String> allowedToolNames = toolPolicy.getAllowedToolsForProfile(toolProfile);
            List<AgentTool> availableTools = toolRegistry.getToolsForProfile(allowedToolNames);

            String systemPrompt = systemPromptBuilder.buildSystemPrompt(availableTools, context);

            // Initialize conversation if first message
            if (session.getMessages().isEmpty()) {
                session.getMessages().add(AgentMessage.system(systemPrompt));
            }

            // Add user message (null when resuming after approval)
            if (userMessage != null) {
                session.getMessages().add(AgentMessage.user(userMessage));
            }

            // Step 2: Create or reuse tool context (preserves approved tools across calls)
            ToolContext toolContext = sessionToolContexts.computeIfAbsent(sessionId, id ->
                    ToolContext.builder()
                            .sessionId(id)
                            .toolProfile(toolProfile)
                            .allowedTools(allowedToolNames)
                            .dryRun(false)
                            .approvedTools(new java.util.HashSet<>())
                            .build());
            // Update allowed tools in case profile changed
            toolContext.setAllowedTools(allowedToolNames);

            // Step 3: Run the agentic loop
            CompletableFuture<AgentResponse> loopResult = runLoop(sessionId, session, toolContext, availableTools, context);

            // Record resolution for learning after loop completes
            final String msg = userMessage;
            return loopResult.thenApply(agentResponse -> {
                if (agentResponse.getToolsUsed() != null && !agentResponse.getToolsUsed().isEmpty()) {
                    try {
                        long durationMs = Instant.now().toEpochMilli() - session.getStartedAt().toEpochMilli();
                        String service = context != null && context.getAdditionalContext() != null
                                ? context.getAdditionalContext() : "unknown";
                        learningService.recordResolution(
                                context != null ? context.getCurrentIncidentId() : null,
                                service, msg, agentResponse.getToolsUsed(), true, durationMs);
                    } catch (Exception e) {
                        log.warn("Failed to record resolution for learning: {}", e.getMessage());
                    }
                }
                return agentResponse;
            });

        } catch (Exception e) {
            log.error("Agent session {} failed: {}", sessionId, e.getMessage(), e);
            return CompletableFuture.completedFuture(
                    AgentResponse.builder()
                            .sessionId(sessionId)
                            .response("Agent error: " + e.getMessage())
                            .error(e.getMessage())
                            .timestamp(Instant.now())
                            .build());
        }
    }

    /**
     * Abort an active agent session.
     */
    public void abort(String sessionId) {
        activeSessions.remove(sessionId);
        sessionToolContexts.remove(sessionId);
        log.info("Agent session {} aborted", sessionId);
    }

    /**
     * Resume the agent loop after an approval has been granted.
     * Marks the tool as approved for this session and re-runs the agent
     * with a system notification so the LLM knows it can proceed.
     */
    @Async("agentExecutor")
    public CompletableFuture<AgentResponse> resumeAfterApproval(String sessionId, String toolName) {
        log.info("Resuming session {} after approval of tool '{}'", sessionId, toolName);

        // Mark the tool as approved in this session's context
        ToolContext toolContext = sessionToolContexts.get(sessionId);
        if (toolContext != null) {
            toolContext.getApprovedTools().add(toolName);
        }

        AgentSession session = activeSessions.get(sessionId);
        if (session == null) {
            log.warn("Cannot resume session {} — session not found. It may have expired.", sessionId);
            return CompletableFuture.completedFuture(
                    AgentResponse.builder()
                            .sessionId(sessionId)
                            .response("Session expired. Please re-ask your question — the tool is now pre-approved.")
                            .toolsUsed(List.of())
                            .iterations(0)
                            .timestamp(Instant.now())
                            .build());
        }

        // Inject a system message telling the LLM the tool has been approved
        session.getMessages().add(AgentMessage.user(
                String.format("The tool '%s' has been approved by a human operator. " +
                              "Please proceed with executing it to complete the task.", toolName)));

        // Re-build context
        SystemPromptBuilder.AgentContext context = SystemPromptBuilder.AgentContext.builder().build();

        // Re-run the agentic loop (reuses the existing conversation history)
        return run(sessionId, null, context);
    }

    /**
     * Internal: run the agent loop. If userMessage is null, the conversation
     * already has the latest message and we just need to continue the loop.
     */
    private CompletableFuture<AgentResponse> runLoop(String sessionId, AgentSession session,
                                                      ToolContext toolContext,
                                                      List<AgentTool> availableTools,
                                                      SystemPromptBuilder.AgentContext context) {
        StringBuilder fullResponse = new StringBuilder();
        List<String> toolsUsed = new ArrayList<>();
        int iterations = 0;

        try {
            while (iterations < MAX_ITERATIONS) {
                iterations++;
                log.debug("Agent loop iteration {} for session {}", iterations, sessionId);

                AgentMessage response = llmClient.chat(session.getMessages(), availableTools);
                session.getMessages().add(response);

                if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                    if (response.getContent() != null) {
                        fullResponse.append(response.getContent());
                    }
                    break;
                }

                if (response.getContent() != null) {
                    fullResponse.append(response.getContent()).append("\n");
                }

                boolean waitingForApproval = false;
                for (AgentMessage.ToolCall toolCall : response.getToolCalls()) {
                    log.info("Executing tool: {} (id: {})", toolCall.getName(), toolCall.getId());

                    if (!toolPolicy.isToolAllowed(toolCall.getName(), toolContext)) {
                        String denied = String.format("Tool '%s' is not allowed by the current policy.", toolCall.getName());
                        session.getMessages().add(AgentMessage.toolResult(toolCall.getId(), denied));
                        continue;
                    }

                    Optional<AgentTool> tool = toolRegistry.getTool(toolCall.getName());
                    if (tool.isEmpty()) {
                        String notFound = String.format("Tool '%s' not found.", toolCall.getName());
                        session.getMessages().add(AgentMessage.toolResult(toolCall.getId(), notFound));
                        continue;
                    }

                    // Check approval: config-driven list OR tool's own flag
                    boolean toolNeedsApproval = properties.getToolPolicy().getApprovalRequired()
                            .contains(toolCall.getName()) || tool.get().requiresApproval();
                    if (toolNeedsApproval && !toolContext.isToolApproved(toolCall.getName())) {
                        try {
                            approvalService.requestApproval(
                                    toolCall.getName(), toolCall.getArguments(), sessionId, null);
                        } catch (Exception e) {
                            log.warn("Failed to create approval request: {}", e.getMessage());
                        }
                        String approvalMsg = String.format(
                                "Tool '%s' requires human approval. An approval request has been created " +
                                "and is visible on the Pending Approvals page. The agent will automatically " +
                                "resume once approved.",
                                toolCall.getName());
                        session.getMessages().add(AgentMessage.toolResult(toolCall.getId(), approvalMsg));
                        waitingForApproval = true;
                        continue;
                    }

                    try {
                        ToolResult result = tool.get().execute(toolCall.getArguments(), toolContext);
                        toolsUsed.add(toolCall.getName());
                        String resultText = result.getTextContent();
                        session.getMessages().add(AgentMessage.toolResult(toolCall.getId(), resultText));
                        log.debug("Tool {} result: {}", toolCall.getName(), truncate(resultText, 200));

                        auditService.log("agent", "TOOL_EXECUTED", toolCall.getName(),
                                Map.of("arguments", toolCall.getArguments() != null ? toolCall.getArguments() : Map.of(),
                                       "success", result.isSuccess()),
                                sessionId, result.isSuccess());
                    } catch (Exception e) {
                        log.error("Tool {} execution failed: {}", toolCall.getName(), e.getMessage());
                        session.getMessages().add(AgentMessage.toolResult(toolCall.getId(),
                                "Error executing tool: " + e.getMessage()));
                        auditService.log("agent", "TOOL_EXECUTED", toolCall.getName(),
                                Map.of("error", e.getMessage()), sessionId, false);
                    }
                }

                // If we're waiting for an approval, stop the loop — it will resume later
                if (waitingForApproval) {
                    fullResponse.append("\n[Waiting for human approval to continue...]");
                    break;
                }

                if (estimateTokens(session.getMessages()) > MAX_CONVERSATION_TOKENS * 0.8) {
                    compactConversation(session);
                }
            }

            if (iterations >= MAX_ITERATIONS) {
                fullResponse.append("\n[Agent reached maximum iterations. Some analysis may be incomplete.]");
            }

            AgentResponse agentResponse = AgentResponse.builder()
                    .sessionId(sessionId)
                    .response(fullResponse.toString())
                    .toolsUsed(toolsUsed)
                    .iterations(iterations)
                    .timestamp(Instant.now())
                    .build();

            log.info("Agent session {} loop completed: {} iterations, {} tools used",
                    sessionId, iterations, toolsUsed.size());

            return CompletableFuture.completedFuture(agentResponse);

        } catch (Exception e) {
            log.error("Agent session {} loop failed: {}", sessionId, e.getMessage(), e);
            return CompletableFuture.completedFuture(
                    AgentResponse.builder()
                            .sessionId(sessionId)
                            .response("Agent error: " + e.getMessage())
                            .error(e.getMessage())
                            .timestamp(Instant.now())
                            .build());
        }
    }

    /**
     * Get the current state of an agent session.
     */
    public Optional<AgentSession> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /**
     * Get all active sessions.
     */
    public Map<String, AgentSession> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }

    /**
     * Compact the conversation to fit within context window limits.
     * Like OpenClaw's automatic summarization.
     */
    private void compactConversation(AgentSession session) {
        log.info("Compacting conversation for session {} ({} messages)",
                session.getSessionId(), session.getMessages().size());

        List<AgentMessage> messages = session.getMessages();
        if (messages.size() <= 4) return; // Keep at least system + last exchange

        // Keep system prompt and last N messages, summarize the rest
        AgentMessage systemMsg = messages.get(0);
        int keepLast = Math.min(10, messages.size() - 1);
        List<AgentMessage> recentMessages = new ArrayList<>(messages.subList(messages.size() - keepLast, messages.size()));

        // Create a summary of the removed messages
        StringBuilder summary = new StringBuilder("Previous conversation summary:\n");
        for (int i = 1; i < messages.size() - keepLast; i++) {
            AgentMessage msg = messages.get(i);
            if (msg.getRole() == AgentMessage.Role.ASSISTANT && msg.getContent() != null) {
                summary.append("- ").append(truncate(msg.getContent(), 100)).append("\n");
            }
        }

        List<AgentMessage> compacted = new ArrayList<>();
        compacted.add(systemMsg);
        compacted.add(AgentMessage.system(summary.toString()));
        compacted.addAll(recentMessages);

        session.setMessages(compacted);
        log.info("Compacted to {} messages", compacted.size());
    }

    private int estimateTokens(List<AgentMessage> messages) {
        int tokens = 0;
        for (AgentMessage msg : messages) {
            if (msg.getContent() != null) {
                tokens += msg.getContent().length() / 4; // Rough approximation
            }
        }
        return tokens;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    @lombok.Data
    @lombok.Builder
    public static class AgentSession {
        private String sessionId;
        private Instant startedAt;
        private List<AgentMessage> messages;
    }

    @lombok.Data
    @lombok.Builder
    public static class AgentResponse {
        private String sessionId;
        private String response;
        private String error;
        private List<String> toolsUsed;
        private int iterations;
        private Instant timestamp;
    }
}
