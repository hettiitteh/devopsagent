package com.example.devopsagent.controller;

import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.memory.IncidentKnowledgeBase;
import com.example.devopsagent.notification.NotificationService;
import com.example.devopsagent.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Incident Management REST API Controller.
 */
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentRepository incidentRepository;
    private final IncidentKnowledgeBase knowledgeBase;
    private final NotificationService notificationService;

    /**
     * List all incidents.
     */
    @GetMapping
    public ResponseEntity<List<Incident>> listIncidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String severity) {

        List<Incident> incidents;
        if (status != null) {
            incidents = incidentRepository.findByStatus(Incident.IncidentStatus.valueOf(status.toUpperCase()));
        } else if (service != null) {
            incidents = incidentRepository.findByService(service);
        } else if (severity != null) {
            incidents = incidentRepository.findBySeverity(Incident.Severity.valueOf(severity.toUpperCase()));
        } else {
            incidents = incidentRepository.findAll();
        }
        return ResponseEntity.ok(incidents);
    }

    /**
     * Get active incidents.
     */
    @GetMapping("/active")
    public ResponseEntity<List<Incident>> getActiveIncidents() {
        return ResponseEntity.ok(incidentRepository.findActiveIncidents());
    }

    /**
     * Get a specific incident.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Incident> getIncident(@PathVariable String id) {
        return incidentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new incident.
     */
    @PostMapping
    public ResponseEntity<Incident> createIncident(@RequestBody Incident incident) {
        incident.setSource("api");
        Incident saved = incidentRepository.save(incident);
        notificationService.notifyIncident(saved);
        return ResponseEntity.ok(saved);
    }

    /**
     * Update an incident.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Incident> updateIncident(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        return incidentRepository.findById(id)
                .map(incident -> {
                    if (updates.containsKey("status")) {
                        incident.setStatus(Incident.IncidentStatus.valueOf(((String) updates.get("status")).toUpperCase()));
                    }
                    if (updates.containsKey("severity")) {
                        incident.setSeverity(Incident.Severity.valueOf(((String) updates.get("severity")).toUpperCase()));
                    }
                    if (updates.containsKey("root_cause")) {
                        incident.setRootCause((String) updates.get("root_cause"));
                    }
                    if (updates.containsKey("resolution")) {
                        incident.setResolution((String) updates.get("resolution"));
                    }
                    if (updates.containsKey("assignee")) {
                        incident.setAssignee((String) updates.get("assignee"));
                    }
                    return ResponseEntity.ok(incidentRepository.save(incident));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Resolve an incident.
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Incident> resolveIncident(@PathVariable String id, @RequestBody Map<String, String> body) {
        return incidentRepository.findById(id)
                .map(incident -> {
                    incident.setStatus(Incident.IncidentStatus.RESOLVED);
                    incident.setResolvedAt(Instant.now());
                    if (body.containsKey("resolution")) {
                        incident.setResolution(body.get("resolution"));
                    }
                    if (body.containsKey("root_cause")) {
                        incident.setRootCause(body.get("root_cause"));
                    }
                    return ResponseEntity.ok(incidentRepository.save(incident));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Search for similar incidents.
     */
    @GetMapping("/search")
    public ResponseEntity<List<Incident>> searchIncidents(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String service) {
        return ResponseEntity.ok(knowledgeBase.searchSimilarIncidents(query, service));
    }

    /**
     * Get incident statistics for a service.
     */
    @GetMapping("/stats/{service}")
    public ResponseEntity<Map<String, Object>> getServiceStats(@PathVariable String service) {
        return ResponseEntity.ok(knowledgeBase.getServiceIncidentStats(service));
    }
}
