package com.example.devopsagent.controller;

import com.example.devopsagent.domain.PlaybookSuggestion;
import com.example.devopsagent.service.PlaybookSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for AI-suggested playbooks.
 */
@RestController
@RequestMapping("/api/playbooks/suggestions")
@RequiredArgsConstructor
public class PlaybookSuggestionController {

    private final PlaybookSuggestionService suggestionService;

    /**
     * List all pending suggestions.
     */
    @GetMapping
    public ResponseEntity<List<PlaybookSuggestion>> getPendingSuggestions() {
        return ResponseEntity.ok(suggestionService.getPendingSuggestions());
    }

    /**
     * Get count of pending suggestions (for badge).
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getPendingCount() {
        return ResponseEntity.ok(Map.of("count", suggestionService.getPendingCount()));
    }

    /**
     * Approve a suggestion and create the playbook.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveSuggestion(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String reviewedBy = body != null ? body.getOrDefault("reviewed_by", "user") : "user";
        try {
            PlaybookSuggestion approved = suggestionService.approveSuggestion(id, reviewedBy);
            return ResponseEntity.ok(Map.of(
                    "id", approved.getId(),
                    "name", approved.getName(),
                    "status", approved.getStatus().name(),
                    "message", "Suggestion approved and playbook created"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Dismiss a suggestion.
     */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissSuggestion(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String reviewedBy = body != null ? body.getOrDefault("reviewed_by", "user") : "user";
        try {
            PlaybookSuggestion dismissed = suggestionService.dismissSuggestion(id, reviewedBy);
            return ResponseEntity.ok(Map.of(
                    "id", dismissed.getId(),
                    "status", dismissed.getStatus().name(),
                    "message", "Suggestion dismissed"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manually trigger suggestion generation (in addition to the scheduled task).
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> triggerGeneration() {
        suggestionService.generateSuggestions();
        long count = suggestionService.getPendingCount();
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "pending_suggestions", count
        ));
    }

    /**
     * Trigger AI-powered playbook suggestion generation using the LLM.
     * Analyzes resolution patterns with LLM reasoning to suggest playbooks
     * beyond what simple frequency counting can detect.
     */
    @PostMapping("/generate-ai")
    public ResponseEntity<Map<String, Object>> triggerAiGeneration() {
        try {
            suggestionService.generateAiSuggestions();
            long count = suggestionService.getPendingCount();
            return ResponseEntity.ok(Map.of(
                    "status", "completed",
                    "pending_suggestions", count,
                    "message", "AI-powered suggestion generation completed"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "AI suggestion generation failed: " + e.getMessage()
            ));
        }
    }
}
