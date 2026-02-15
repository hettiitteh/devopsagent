package com.example.devopsagent.controller;

import com.example.devopsagent.domain.PlaybookExecution;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.repository.PlaybookExecutionRepository;
import com.example.devopsagent.service.PlaybookGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Playbook REST API Controller.
 */
@RestController
@RequestMapping("/api/playbooks")
@RequiredArgsConstructor
public class PlaybookController {

    private final PlaybookEngine playbookEngine;
    private final PlaybookExecutionRepository executionRepository;
    private final PlaybookGeneratorService playbookGeneratorService;

    /**
     * List all available playbooks (text format, backward compat).
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> listPlaybooks() {
        return ResponseEntity.ok(Map.of("playbooks", playbookEngine.listPlaybooks().getTextContent()));
    }

    /**
     * List all playbooks as structured JSON.
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listPlaybooksStructured() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Playbook pb : playbookEngine.getAllPlaybooks()) {
            result.add(Map.of(
                    "id", pb.getId() != null ? pb.getId() : "",
                    "name", pb.getName() != null ? pb.getName() : "",
                    "description", pb.getDescription() != null ? pb.getDescription() : "",
                    "steps", pb.getSteps() != null ? pb.getSteps().size() : 0,
                    "approvalRequired", pb.isApprovalRequired(),
                    "tags", pb.getTags() != null ? pb.getTags() : List.of(),
                    "author", pb.getAuthor() != null ? pb.getAuthor() : ""
            ));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific playbook.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Playbook> getPlaybook(@PathVariable String id) {
        return playbookEngine.getPlaybook(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create or update a playbook from a full definition.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlaybook(@RequestBody Playbook playbook) {
        try {
            Playbook saved = playbookGeneratorService.savePlaybook(playbook, Map.of("source", "ui"));
            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "name", saved.getName(),
                    "steps", saved.getSteps() != null ? saved.getSteps().size() : 0,
                    "message", "Playbook saved successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a playbook.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deletePlaybook(@PathVariable String id) {
        boolean deleted = playbookGeneratorService.deletePlaybook(id);
        if (deleted) {
            return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Execute a playbook.
     */
    @PostMapping("/{id}/execute")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> executePlaybook(
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {

        String incidentId = (String) request.getOrDefault("incident_id", null);
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("parameters", Map.of());
        boolean dryRun = request.containsKey("dry_run") && (boolean) request.get("dry_run");

        var result = playbookEngine.executePlaybook(id, incidentId, params, dryRun);
        return ResponseEntity.ok(Map.of("result", result.getTextContent()));
    }

    /**
     * List playbook executions.
     */
    @GetMapping("/executions")
    public ResponseEntity<List<PlaybookExecution>> listExecutions(
            @RequestParam(required = false) String playbookId,
            @RequestParam(required = false) String incidentId) {

        List<PlaybookExecution> executions;
        if (playbookId != null) {
            executions = executionRepository.findByPlaybookId(playbookId);
        } else if (incidentId != null) {
            executions = executionRepository.findByIncidentId(incidentId);
        } else {
            executions = executionRepository.findAll();
        }
        return ResponseEntity.ok(executions);
    }

    /**
     * Generate a playbook from an agent session's tool sequence.
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generatePlaybook(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String name = body.getOrDefault("name", "auto-generated-playbook");
        String description = body.get("description");

        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }

        try {
            Playbook generated = playbookGeneratorService.generateAndSave(sessionId, name, description);
            return ResponseEntity.ok(Map.of(
                    "id", generated.getId(),
                    "name", generated.getName(),
                    "steps", generated.getSteps().size(),
                    "message", "Playbook generated and saved successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reload playbooks from disk.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadPlaybooks() {
        playbookEngine.loadPlaybooks();
        return ResponseEntity.ok(Map.of("status", "reloaded"));
    }
}
