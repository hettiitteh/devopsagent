package com.example.devopsagent.controller;

import com.example.devopsagent.domain.PlaybookDefinition;
import com.example.devopsagent.domain.PlaybookExecution;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.repository.PlaybookDefinitionRepository;
import com.example.devopsagent.repository.PlaybookExecutionRepository;
import com.example.devopsagent.service.PlaybookGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final PlaybookDefinitionRepository definitionRepository;
    private final PlaybookGeneratorService playbookGeneratorService;

    /**
     * List all available playbooks (text format, backward compat).
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> listPlaybooks() {
        return ResponseEntity.ok(Map.of("playbooks", playbookEngine.listPlaybooks().getTextContent()));
    }

    /**
     * List all playbooks as structured JSON with full trigger data.
     * Returns all playbook definitions from the DB (including disabled ones).
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listPlaybooksStructured() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlaybookDefinition def : definitionRepository.findAllByOrderByCreatedAtDesc()) {
            Playbook pb = def.toPlaybook();
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", pb.getId() != null ? pb.getId() : "");
            entry.put("name", pb.getName() != null ? pb.getName() : "");
            entry.put("description", pb.getDescription() != null ? pb.getDescription() : "");
            entry.put("steps", pb.getSteps() != null ? pb.getSteps().size() : 0);
            entry.put("stepsDetail", pb.getSteps() != null ? pb.getSteps() : List.of());
            entry.put("approvalRequired", pb.isApprovalRequired());
            entry.put("tags", pb.getTags() != null ? pb.getTags() : List.of());
            entry.put("author", pb.getAuthor() != null ? pb.getAuthor() : "");
            entry.put("triggers", pb.getTriggers() != null ? pb.getTriggers() : List.of());
            entry.put("enabled", def.isEnabled());
            entry.put("source", def.getSource() != null ? def.getSource() : "");
            entry.put("version", pb.getVersion() != null ? pb.getVersion() : "1.0");
            entry.put("maxExecutionTimeSeconds", pb.getMaxExecutionTimeSeconds());
            result.add(entry);
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
     * Update an existing playbook definition.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updatePlaybook(@PathVariable String id,
                                                               @RequestBody Playbook playbook) {
        return definitionRepository.findById(id)
                .map(existing -> {
                    // Update the definition from the incoming playbook
                    playbook.setId(id); // preserve the ID
                    PlaybookDefinition updated = PlaybookDefinition.fromPlaybook(playbook,
                            existing.getSource() != null ? existing.getSource() : "ui");
                    updated.setEnabled(existing.isEnabled());
                    updated.setCreatedAt(existing.getCreatedAt());
                    definitionRepository.save(updated);

                    // Refresh engine cache
                    playbookEngine.loadPlaybooks();

                    return ResponseEntity.ok(Map.<String, Object>of(
                            "id", id,
                            "name", playbook.getName(),
                            "message", "Playbook updated successfully"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toggle a playbook's enabled state.
     */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> togglePlaybook(@PathVariable String id) {
        return definitionRepository.findById(id)
                .map(def -> {
                    def.setEnabled(!def.isEnabled());
                    definitionRepository.save(def);
                    playbookEngine.loadPlaybooks();
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "id", id,
                            "enabled", def.isEnabled(),
                            "message", (def.isEnabled() ? "Enabled" : "Disabled") + " playbook: " + def.getName()
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
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
