package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.domain.PlaybookSuggestion;
import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import com.example.devopsagent.service.LearningService;
import com.example.devopsagent.service.PlaybookSuggestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Learning Query Tool - Gives the LLM full access to the agent's learning system.
 *
 * Allows the agent to:
 * - View aggregated insights across all services
 * - Query resolution history for a specific service
 * - Get recommended approaches based on past successes
 * - View pending playbook suggestions from the AI
 * - Compare success rates across services
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningQueryTool implements AgentTool {

    private final LearningService learningService;
    private final ResolutionRecordRepository resolutionRepository;
    private final PlaybookSuggestionService suggestionService;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "learning_query"; }

    @Override
    public String getDescription() {
        return "Query the agent's learning system to review past resolution patterns, success rates, " +
               "and AI-suggested playbooks. Use this to understand what the agent has learned from " +
               "past incidents, find recommended approaches for a service, compare performance " +
               "across services, or check pending playbook suggestions.";
    }

    @Override
    public String getCategory() { return "knowledge"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of("type", "string", "description",
                    "Action to perform: insights, recommendations, history, suggestions, service_stats"),
                "service", Map.of("type", "string", "description",
                    "Service name (required for recommendations, history, service_stats)"),
                "limit", Map.of("type", "integer", "description",
                    "Max number of records to return (default 10)", "default", 10)
            ),
            "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String action = (String) parameters.get("action");
        String service = (String) parameters.get("service");
        int limit = parameters.containsKey("limit")
                ? ((Number) parameters.get("limit")).intValue() : 10;

        return switch (action.toLowerCase()) {
            case "insights" -> getInsights();
            case "recommendations" -> getRecommendations(service);
            case "history" -> getHistory(service, limit);
            case "suggestions" -> getSuggestions();
            case "service_stats" -> getServiceStats(service);
            default -> ToolResult.error("Unknown action: " + action +
                    ". Use: insights, recommendations, history, suggestions, service_stats");
        };
    }

    /**
     * Full aggregated insights: totals, top patterns, per-service stats.
     */
    private ToolResult getInsights() {
        Map<String, Object> insights = learningService.getInsights();

        int totalResolutions = (int) insights.get("total_resolutions");
        if (totalResolutions == 0) {
            return ToolResult.text("No resolution data recorded yet. " +
                    "The learning system will start capturing data as incidents are resolved " +
                    "via chat, playbooks, or automatic monitoring recovery.");
        }

        StringBuilder sb = new StringBuilder("# Learning Insights\n\n");
        sb.append(String.format("Total resolutions recorded: %d\n", totalResolutions));
        sb.append(String.format("Total successful: %s\n", insights.get("total_successful")));
        sb.append(String.format("Overall success rate: %.1f%%\n\n", ((Number) insights.get("overall_success_rate")).doubleValue()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) insights.get("patterns");
        if (!patterns.isEmpty()) {
            sb.append("## Top Resolution Patterns\n");
            for (int i = 0; i < patterns.size(); i++) {
                Map<String, Object> p = patterns.get(i);
                String seq = formatToolSequence((String) p.get("sequence"));
                sb.append(String.format("%d. %s (used %d time(s))\n", i + 1, seq, ((Number) p.get("count")).longValue()));
            }
            sb.append("\n");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serviceStats = (List<Map<String, Object>>) insights.get("service_stats");
        if (!serviceStats.isEmpty()) {
            sb.append("## Per-Service Statistics\n");
            for (Map<String, Object> s : serviceStats) {
                sb.append(String.format("- **%s**: %s total, %s successful, %.1f%% success rate, avg resolution %ds\n",
                        s.get("service"), s.get("total"), s.get("successful"),
                        ((Number) s.get("success_rate")).doubleValue(),
                        ((Number) s.get("avg_resolution_ms")).longValue() / 1000));
            }
        }

        return ToolResult.text(sb.toString());
    }

    /**
     * Get recommended approach for a specific service.
     */
    private ToolResult getRecommendations(String service) {
        if (service == null || service.isBlank()) {
            return ToolResult.error("Service name is required for recommendations. Provide the 'service' parameter.");
        }

        String recommendation = learningService.getRecommendedApproach(service);
        double successRate = learningService.getSuccessRate(service);

        if (recommendation == null) {
            return ToolResult.text(String.format(
                    "No resolution data found for service '%s'. " +
                    "The learning system has not yet recorded any successful resolutions for this service.", service));
        }

        return ToolResult.text(String.format(
                "# Recommendations for '%s'\n\n%s\nSuccess rate: %.1f%%\n\n" +
                "This recommendation is based on the most frequently successful tool sequence " +
                "observed in past incident resolutions for this service.",
                service, recommendation, successRate));
    }

    /**
     * Get resolution history for a service (or all services if none specified).
     */
    private ToolResult getHistory(String service, int limit) {
        List<ResolutionRecord> records;
        if (service != null && !service.isBlank()) {
            records = resolutionRepository.findByServiceOrderByCreatedAtDesc(service);
        } else {
            records = resolutionRepository.findAll().stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        }

        if (records.isEmpty()) {
            return ToolResult.text(service != null
                    ? String.format("No resolution history found for service '%s'.", service)
                    : "No resolution history recorded yet.");
        }

        List<ResolutionRecord> limited = records.stream().limit(limit).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# Resolution History%s (%d of %d records)\n\n",
                service != null ? " for '" + service + "'" : " (all services)",
                limited.size(), records.size()));

        for (ResolutionRecord r : limited) {
            String tools = formatToolSequence(r.getToolSequence());
            sb.append(String.format("- [%s] **%s** | Service: %s | Tools: %s | %s | %ds\n",
                    r.getCreatedAt().toString().substring(0, 19),
                    r.getIncidentTitle() != null ? r.getIncidentTitle() : "untitled",
                    r.getService() != null ? r.getService() : "unknown",
                    tools,
                    r.isSuccess() ? "SUCCESS" : "FAILED",
                    r.getResolutionTimeMs() / 1000));
        }

        return ToolResult.text(sb.toString());
    }

    /**
     * Get pending AI playbook suggestions.
     */
    private ToolResult getSuggestions() {
        List<PlaybookSuggestion> suggestions = suggestionService.getPendingSuggestions();

        if (suggestions.isEmpty()) {
            return ToolResult.text("No pending playbook suggestions. " +
                    "The AI suggestion system analyzes resolution patterns every 5 minutes. " +
                    "When it detects a tool sequence that has successfully resolved issues " +
                    "at least 2 times and no existing playbook covers it, it creates a suggestion.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# Pending Playbook Suggestions (%d)\n\n", suggestions.size()));
        sb.append("These suggestions are based on recurring successful resolution patterns. " +
                "A human can approve or dismiss them in the Playbooks UI.\n\n");

        for (PlaybookSuggestion s : suggestions) {
            String tools = formatToolSequence(s.getToolSequence());
            sb.append(String.format("- **%s** (ID: %s)\n  Service: %s | Seen %d time(s) | Avg resolution: %ds\n  Tools: %s\n  %s\n\n",
                    s.getName(), s.getId(),
                    s.getService(), s.getFrequency(),
                    s.getAvgResolutionMs() / 1000,
                    tools,
                    s.getDescription() != null ? s.getDescription() : ""));
        }

        return ToolResult.text(sb.toString());
    }

    /**
     * Get detailed stats for a specific service.
     */
    private ToolResult getServiceStats(String service) {
        if (service == null || service.isBlank()) {
            return ToolResult.error("Service name is required. Provide the 'service' parameter.");
        }

        long total = resolutionRepository.countByService(service);
        if (total == 0) {
            return ToolResult.text(String.format("No resolution data for service '%s'.", service));
        }

        long successful = resolutionRepository.countSuccessfulByService(service);
        double successRate = (double) successful / total * 100.0;

        List<ResolutionRecord> allRecords = resolutionRepository.findByServiceOrderByCreatedAtDesc(service);
        List<ResolutionRecord> successRecords = allRecords.stream().filter(ResolutionRecord::isSuccess).toList();

        long avgTime = successRecords.isEmpty() ? 0 :
                (long) successRecords.stream().mapToLong(ResolutionRecord::getResolutionTimeMs).average().orElse(0);

        // Find most common tool sequences
        Map<String, Long> patterns = successRecords.stream()
                .collect(Collectors.groupingBy(ResolutionRecord::getToolSequence, Collectors.counting()));
        List<Map.Entry<String, Long>> topPatterns = patterns.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# Service Stats: %s\n\n", service));
        sb.append(String.format("Total resolutions: %d\n", total));
        sb.append(String.format("Successful: %d\n", successful));
        sb.append(String.format("Failed: %d\n", total - successful));
        sb.append(String.format("Success rate: %.1f%%\n", successRate));
        sb.append(String.format("Average resolution time: %ds\n\n", avgTime / 1000));

        if (!topPatterns.isEmpty()) {
            sb.append("## Most Common Successful Patterns\n");
            for (int i = 0; i < topPatterns.size(); i++) {
                String seq = formatToolSequence(topPatterns.get(i).getKey());
                sb.append(String.format("%d. %s (%d time(s))\n", i + 1, seq, topPatterns.get(i).getValue()));
            }
        }

        // Recent history
        List<ResolutionRecord> recent = allRecords.stream().limit(5).toList();
        if (!recent.isEmpty()) {
            sb.append("\n## Recent Resolutions\n");
            for (ResolutionRecord r : recent) {
                sb.append(String.format("- [%s] %s | %s | %ds\n",
                        r.getCreatedAt().toString().substring(0, 19),
                        r.getIncidentTitle() != null ? r.getIncidentTitle() : "untitled",
                        r.isSuccess() ? "SUCCESS" : "FAILED",
                        r.getResolutionTimeMs() / 1000));
            }
        }

        return ToolResult.text(sb.toString());
    }

    /**
     * Format a JSON tool sequence into a readable string.
     */
    @SuppressWarnings("unchecked")
    private String formatToolSequence(String json) {
        if (json == null) return "none";
        try {
            List<String> tools = objectMapper.readValue(json, List.class);
            return String.join(" -> ", tools);
        } catch (Exception e) {
            return json;
        }
    }
}
