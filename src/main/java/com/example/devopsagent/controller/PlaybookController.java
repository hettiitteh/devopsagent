package com.example.devopsagent.controller;

import com.example.devopsagent.domain.PlaybookExecution;
import com.example.devopsagent.playbook.Playbook;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.repository.PlaybookExecutionRepository;
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

    /**
     * List all available playbooks.
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> listPlaybooks() {
        return ResponseEntity.ok(Map.of("playbooks", playbookEngine.listPlaybooks().getTextContent()));
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
     * Reload playbooks from disk.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadPlaybooks() {
        playbookEngine.loadPlaybooks();
        return ResponseEntity.ok(Map.of("status", "reloaded"));
    }
}
