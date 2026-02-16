package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.agent.LlmClient;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.PlaybookSuggestion;
import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.embedding.EmbeddingService;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.repository.PlaybookSuggestionRepository;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes resolution patterns from the learning system and suggests
 * new playbooks for recurring tool sequences that don't already exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybookSuggestionService {

    private final ResolutionRecordRepository resolutionRepository;
    private final PlaybookSuggestionRepository suggestionRepository;
    private final PlaybookEngine playbookEngine;
    private final PlaybookGeneratorService playbookGeneratorService;
    private final AuditService auditService;
    private final LearningService learningService;
    private final LlmClient llmClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;

    private static final int MIN_FREQUENCY = 2; // Minimum times a pattern must appear

    /**
     * Periodically scan resolution records and generate suggestions
     * for recurring patterns that don't have playbooks yet.
     */
    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    public void generateSuggestions() {
        try {
            List<ResolutionRecord> allSuccessful = resolutionRepository.findAll().stream()
                    .filter(ResolutionRecord::isSuccess)
                    .toList();

            if (allSuccessful.isEmpty()) return;

            // Group by tool sequence and count frequency
            Map<String, List<ResolutionRecord>> bySequence = allSuccessful.stream()
                    .filter(r -> r.getToolSequence() != null)
                    .filter(r -> !isAutomatedPattern(r.getToolSequence()))
                    .collect(Collectors.groupingBy(ResolutionRecord::getToolSequence));

            for (Map.Entry<String, List<ResolutionRecord>> entry : bySequence.entrySet()) {
                String toolSequenceJson = entry.getKey();
                List<ResolutionRecord> records = entry.getValue();

                if (records.size() < MIN_FREQUENCY) continue;

                // Check if a suggestion already exists for this pattern
                Optional<PlaybookSuggestion> existing = suggestionRepository
                        .findByToolSequenceAndStatus(toolSequenceJson, PlaybookSuggestion.SuggestionStatus.SUGGESTED);
                if (existing.isPresent()) {
                    // Update frequency if changed
                    PlaybookSuggestion ex = existing.get();
                    if (ex.getFrequency() != records.size()) {
                        ex.setFrequency(records.size());
                        suggestionRepository.save(ex);
                    }
                    continue;
                }

                // Check if this pattern already has a playbook
                if (matchesExistingPlaybook(toolSequenceJson)) continue;

                // Check if this pattern was previously dismissed
                Optional<PlaybookSuggestion> dismissed = suggestionRepository
                        .findByToolSequenceAndStatus(toolSequenceJson, PlaybookSuggestion.SuggestionStatus.DISMISSED);
                if (dismissed.isPresent()) continue;

                // Create a new suggestion
                List<String> tools = parseToolSequence(toolSequenceJson);
                String suggestedName = generateName(tools);
                String primaryService = records.stream()
                        .map(ResolutionRecord::getService)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                        .entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("unknown");

                long avgTime = (long) records.stream()
                        .mapToLong(ResolutionRecord::getResolutionTimeMs)
                        .average().orElse(0);

                PlaybookSuggestion suggestion = PlaybookSuggestion.builder()
                        .name(suggestedName)
                        .description(String.format(
                                "Auto-suggested playbook based on %d successful resolutions for service '%s'. " +
                                "Tool sequence: %s. Average resolution time: %ds.",
                                records.size(), primaryService,
                                String.join(" -> ", tools),
                                avgTime / 1000))
                        .toolSequence(toolSequenceJson)
                        .service(primaryService)
                        .frequency(records.size())
                        .avgResolutionMs(avgTime)
                        .status(PlaybookSuggestion.SuggestionStatus.SUGGESTED)
                        .createdAt(Instant.now())
                        .build();

                suggestionRepository.save(suggestion);
                log.info("Created playbook suggestion '{}' (frequency: {}, service: {})",
                        suggestedName, records.size(), primaryService);

                auditService.log("system", "PLAYBOOK_SUGGESTED", suggestion.getId(),
                        Map.of("name", suggestedName, "frequency", records.size(),
                               "service", primaryService));
            }
        } catch (Exception e) {
            log.error("Failed to generate playbook suggestions: {}", e.getMessage());
        }

        // After frequency-based pass, run AI-powered suggestions if enabled
        if (agentProperties.getSuggestions().isAiEnabled()) {
            try {
                generateAiSuggestions();
            } catch (Exception e) {
                log.error("Failed to generate AI-powered suggestions: {}", e.getMessage());
            }
        }
    }

    /**
     * Use the LLM to analyze resolution patterns and suggest new playbooks
     * that go beyond simple frequency counting. The LLM can reason about
     * tool combinations, service patterns, and gaps in existing playbooks.
     */
    public void generateAiSuggestions() {
        log.info("Generating AI-powered playbook suggestions...");

        // 1. Gather data
        Map<String, Object> insights = learningService.getInsights();
        List<ResolutionRecord> recentRecords = resolutionRepository.findAll().stream()
                .sorted(Comparator.comparing(ResolutionRecord::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .toList();

        if (recentRecords.isEmpty()) {
            log.info("No resolution records available for AI suggestion generation");
            return;
        }

        Collection<Playbook> existingPlaybooks = playbookEngine.getAllPlaybooks();

        // 2. Build structured prompt
        StringBuilder dataSection = new StringBuilder();
        dataSection.append("## Resolution Data\n");
        try {
            dataSection.append(objectMapper.writeValueAsString(insights));
        } catch (Exception e) {
            dataSection.append("(unable to serialize insights)");
        }
        dataSection.append("\n\n## Recent Resolutions\n");
        for (ResolutionRecord r : recentRecords) {
            dataSection.append(String.format("- Service: %s, Title: %s, Tools: %s, Success: %s, Time: %dms\n",
                    r.getService(), r.getIncidentTitle(), r.getToolSequence(), r.isSuccess(), r.getResolutionTimeMs()));
        }
        dataSection.append("\n## Existing Playbooks\n");
        for (Playbook pb : existingPlaybooks) {
            List<String> tools = pb.getSteps() != null
                    ? pb.getSteps().stream().map(Playbook.Step::getTool).toList()
                    : List.of();
            dataSection.append(String.format("- ID: %s, Name: %s, Tools: %s\n",
                    pb.getId(), pb.getName(), tools));
        }

        String systemPrompt = """
                You are an SRE analyzing resolution patterns to recommend new automated playbooks.
                
                %s
                
                ## Playbook Authoring Standards (MANDATORY)
                Every playbook you suggest MUST follow this 4-step pattern:
                1. **Verify the Problem** — health_check or docker_exec (inspect) to confirm the issue. onFailure: "continue".
                2. **Collect Diagnostics** — log_search, network_diag, docker_exec, or system_info to gather context. onFailure: "continue".
                3. **Remediate** — service_restart, docker_exec, or kubectl_exec to fix the issue. onFailure: "abort".
                   For service_restart: ALWAYS include service_type ("docker"/"kubernetes"/"systemd"), service_name, and graceful.
                4. **Verify Recovery** — health_check with retry settings. onFailure: "retry", maxRetries: 3, retryDelaySeconds: 5.
                
                CRITICAL RULES:
                - NEVER leave step parameters empty ({}). Every step MUST have fully specified parameters.
                - Use the service name from triggerService in the parameters (e.g. container name, service_name).
                - Include the service health URL in health_check steps.
                
                ## Example of a correct step list
                [
                  {"name": "Verify service down", "tool": "health_check", "parameters": {"url": "http://localhost:3306"}, "on_failure": "continue"},
                  {"name": "Inspect container", "tool": "docker_exec", "parameters": {"action": "inspect", "container": "local-mysql"}, "on_failure": "continue"},
                  {"name": "Restart service", "tool": "service_restart", "parameters": {"service_type": "docker", "service_name": "local-mysql", "graceful": true}, "on_failure": "abort"},
                  {"name": "Verify recovery", "tool": "health_check", "parameters": {"url": "http://localhost:3306"}, "on_failure": "retry", "maxRetries": 3, "retryDelaySeconds": 5}
                ]
                
                ## Task
                Suggest 1-3 NEW playbooks that automate recurring patterns not covered by existing playbooks.
                For each, respond with a JSON object: {"name": "...", "description": "...", "reasoning": "...", "steps": [{"name": "...", "tool": "...", "parameters": {...}, "on_failure": "..."}], "triggerService": "...", "triggerSeverity": "..."}.
                Respond ONLY with a JSON array. No markdown, no explanation outside the JSON.
                """.formatted(dataSection.toString());

        // 3. Call LLM
        List<AgentMessage> messages = List.of(
                AgentMessage.system(systemPrompt),
                AgentMessage.user("Analyze the resolution data and suggest new playbooks.")
        );
        AgentMessage response = llmClient.chat(messages, List.of());
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            log.warn("LLM returned empty response for AI suggestions");
            return;
        }

        // 4. Parse JSON array from response
        try {
            // Strip markdown code fences if present
            content = content.trim();
            if (content.startsWith("```")) {
                content = content.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }

            JsonNode rootNode = objectMapper.readTree(content);
            if (!rootNode.isArray()) {
                log.warn("LLM response is not a JSON array for AI suggestions");
                return;
            }

            for (JsonNode node : rootNode) {
                String name = node.has("name") ? node.get("name").asText() : "AI Suggested Playbook";
                String description = node.has("description") ? node.get("description").asText() : "";
                String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : "";
                String triggerService = node.has("triggerService") ? node.get("triggerService").asText() : "*";
                String triggerSeverity = node.has("triggerSeverity") ? node.get("triggerSeverity").asText() : "*";

                // Build tool sequence from steps
                List<String> tools = new ArrayList<>();
                if (node.has("steps") && node.get("steps").isArray()) {
                    for (JsonNode step : node.get("steps")) {
                        if (step.has("tool")) {
                            tools.add(step.get("tool").asText());
                        }
                    }
                }
                if (tools.isEmpty()) continue;

                String toolSequenceJson = objectMapper.writeValueAsString(tools);

                // Skip if already suggested or existing playbook covers it
                Optional<PlaybookSuggestion> existingSuggestion = suggestionRepository
                        .findByToolSequenceAndStatus(toolSequenceJson, PlaybookSuggestion.SuggestionStatus.SUGGESTED);
                if (existingSuggestion.isPresent()) continue;
                if (matchesExistingPlaybook(toolSequenceJson)) continue;

                // Check if this name already exists
                boolean nameExists = suggestionRepository.findByStatus(PlaybookSuggestion.SuggestionStatus.SUGGESTED).stream()
                        .anyMatch(s -> s.getName().equalsIgnoreCase(name));
                if (nameExists) continue;

                PlaybookSuggestion suggestion = PlaybookSuggestion.builder()
                        .name(name)
                        .description(description + (reasoning.isEmpty() ? "" : "\n\nReasoning: " + reasoning))
                        .toolSequence(toolSequenceJson)
                        .service(triggerService)
                        .frequency(0) // AI-generated, not frequency-based
                        .avgResolutionMs(0)
                        .status(PlaybookSuggestion.SuggestionStatus.SUGGESTED)
                        .createdAt(Instant.now())
                        .build();

                suggestionRepository.save(suggestion);
                log.info("Created AI-powered playbook suggestion '{}' for service '{}'", name, triggerService);

                auditService.log("system", "AI_PLAYBOOK_SUGGESTED", suggestion.getId(),
                        Map.of("name", name, "service", triggerService, "reasoning", reasoning));
            }
        } catch (Exception e) {
            log.error("Failed to parse AI suggestions response: {}", e.getMessage());
        }
    }

    /**
     * Approve a suggestion: convert it into a real playbook and save to disk.
     */
    public PlaybookSuggestion approveSuggestion(String suggestionId, String reviewedBy) {
        PlaybookSuggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + suggestionId));

        if (suggestion.getStatus() != PlaybookSuggestion.SuggestionStatus.SUGGESTED) {
            throw new IllegalStateException("Suggestion already reviewed: " + suggestion.getStatus());
        }

        // Convert to a real playbook
        List<String> tools = parseToolSequence(suggestion.getToolSequence());
        List<Playbook.Step> steps = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            Map<String, Object> stepParams = getDefaultParamsForTool(tools.get(i));
            steps.add(Playbook.Step.builder()
                    .order(i + 1)
                    .name(tools.get(i).replace("_", " "))
                    .tool(tools.get(i))
                    .parameters(stepParams)
                    .onFailure("continue")
                    .build());
        }

        List<Playbook.TriggerCondition> triggers = new ArrayList<>();
        // Always create a trigger so the playbook can auto-fire.
        // Use the specific service if known, otherwise wildcard "*" to match any unhealthy service.
        String triggerService = (suggestion.getService() != null && !"unknown".equals(suggestion.getService()))
                ? suggestion.getService() : "*";
        triggers.add(Playbook.TriggerCondition.builder()
                .type("service_unhealthy")
                .service(triggerService)
                .severities(List.of("HIGH", "CRITICAL"))
                .build());

        Playbook playbook = Playbook.builder()
                .name(suggestion.getName())
                .description(suggestion.getDescription())
                .approvalRequired(false)
                .maxExecutionTimeSeconds(300)
                .tags(List.of("ai-suggested", "auto-generated"))
                .steps(steps)
                .triggers(triggers)
                .build();

        playbookGeneratorService.savePlaybook(playbook,
                Map.of("source", "ai-suggestion", "suggestion_id", suggestionId));

        // Update suggestion status
        suggestion.setStatus(PlaybookSuggestion.SuggestionStatus.APPROVED);
        suggestion.setReviewedAt(Instant.now());
        suggestion.setReviewedBy(reviewedBy);
        suggestionRepository.save(suggestion);

        auditService.log(reviewedBy != null ? reviewedBy : "user", "SUGGESTION_APPROVED",
                suggestionId, Map.of("playbook_name", suggestion.getName()));

        return suggestion;
    }

    /**
     * Dismiss a suggestion.
     */
    public PlaybookSuggestion dismissSuggestion(String suggestionId, String reviewedBy) {
        PlaybookSuggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + suggestionId));

        suggestion.setStatus(PlaybookSuggestion.SuggestionStatus.DISMISSED);
        suggestion.setReviewedAt(Instant.now());
        suggestion.setReviewedBy(reviewedBy);
        suggestionRepository.save(suggestion);

        auditService.log(reviewedBy != null ? reviewedBy : "user", "SUGGESTION_DISMISSED",
                suggestionId, Map.of("playbook_name", suggestion.getName()));

        return suggestion;
    }

    /**
     * Get all pending suggestions.
     */
    public List<PlaybookSuggestion> getPendingSuggestions() {
        return suggestionRepository.findByStatus(PlaybookSuggestion.SuggestionStatus.SUGGESTED);
    }

    /**
     * Get count of pending suggestions (for badge).
     */
    public long getPendingCount() {
        return suggestionRepository.countByStatus(PlaybookSuggestion.SuggestionStatus.SUGGESTED);
    }

    // --- Private helpers ---

    /**
     * Checks if a tool sequence is from an automated source (playbook or monitoring)
     * which should not be suggested as new playbooks.
     */
    private boolean isAutomatedPattern(String toolSequenceJson) {
        try {
            List<String> tools = objectMapper.readValue(toolSequenceJson, new TypeReference<>() {});
            return tools.stream().anyMatch(t -> t.startsWith("playbook:") || t.startsWith("monitoring:"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a tool sequence already matches an existing playbook.
     * Uses exact list comparison first, then embedding similarity as a fallback
     * to detect near-duplicate sequences (e.g. same tools in slightly different order).
     */
    private boolean matchesExistingPlaybook(String toolSequenceJson) {
        List<String> tools = parseToolSequence(toolSequenceJson);
        for (Playbook pb : playbookEngine.getAllPlaybooks()) {
            if (pb.getSteps() == null) continue;
            List<String> pbTools = pb.getSteps().stream()
                    .map(Playbook.Step::getTool)
                    .toList();
            // Exact match
            if (pbTools.equals(tools)) return true;
        }

        // Embedding similarity fallback: check if tool sequence is semantically
        // very close to any existing playbook's tool list (cosine > 0.92)
        if (embeddingService.isEnabled()) {
            try {
                String candidateText = "Tools: " + String.join(", ", tools);
                float[] candidateVec = embeddingService.embed(candidateText);
                if (candidateVec != null) {
                    for (Playbook pb : playbookEngine.getAllPlaybooks()) {
                        if (pb.getSteps() == null) continue;
                        List<String> pbTools = pb.getSteps().stream()
                                .map(Playbook.Step::getTool)
                                .toList();
                        String pbText = "Tools: " + String.join(", ", pbTools);
                        float[] pbVec = embeddingService.embed(pbText);
                        if (pbVec != null) {
                            double sim = EmbeddingService.cosineSimilarity(candidateVec, pbVec);
                            if (sim > 0.92) {
                                log.debug("Tool sequence {} is semantically similar (cosine={}) to playbook '{}' — treating as duplicate",
                                        tools, sim, pb.getName());
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Embedding-based playbook dedup check failed: {}", e.getMessage());
            }
        }

        return false;
    }

    /**
     * Returns sensible default parameters for known tools using variable placeholders.
     * These placeholders are resolved at execution time from the playbook-level params
     * passed by autoTriggerPlaybooks (e.g. service_name).
     */
    private Map<String, Object> getDefaultParamsForTool(String toolName) {
        return switch (toolName) {
            case "service_restart" -> new HashMap<>(Map.of(
                    "service_type", "docker",
                    "service_name", "${service_name}",
                    "graceful", true));
            case "health_check" -> new HashMap<>(Map.of(
                    "url", "${service_url}"));
            case "docker_exec" -> new HashMap<>(Map.of(
                    "action", "inspect",
                    "container", "${service_name}"));
            default -> new HashMap<>();
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> parseToolSequence(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Generate a human-friendly name from a tool sequence.
     */
    private String generateName(List<String> tools) {
        if (tools.isEmpty()) return "Auto-suggested Playbook";

        Map<String, String> toolLabels = Map.ofEntries(
                Map.entry("health_check", "Health Check"),
                Map.entry("docker_exec", "Docker Inspect"),
                Map.entry("kubectl_exec", "Kubernetes"),
                Map.entry("service_restart", "Service Restart"),
                Map.entry("log_search", "Log Analysis"),
                Map.entry("metrics_query", "Metrics Check"),
                Map.entry("network_diag", "Network Diagnosis"),
                Map.entry("system_info", "System Info"),
                Map.entry("ssh_exec", "SSH Command"),
                Map.entry("incident_manage", "Incident Management"),
                Map.entry("alert_manage", "Alert Management")
        );

        List<String> labels = tools.stream()
                .map(t -> toolLabels.getOrDefault(t, t.replace("_", " ")))
                .distinct()
                .limit(3)
                .toList();

        return String.join(" + ", labels) + " Playbook";
    }
}
