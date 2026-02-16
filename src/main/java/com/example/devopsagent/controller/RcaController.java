package com.example.devopsagent.controller;

import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.RcaReport;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.RcaReportRepository;
import com.example.devopsagent.service.RcaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for Root Cause Analysis reports.
 */
@RestController
@RequestMapping("/api/rca")
@RequiredArgsConstructor
public class RcaController {

    private final RcaService rcaService;
    private final RcaReportRepository rcaRepository;
    private final IncidentRepository incidentRepository;

    /**
     * List all RCAs, optionally filtered by status.
     */
    @GetMapping
    public ResponseEntity<List<RcaReport>> listRcas(
            @RequestParam(required = false) String status) {
        if (status != null) {
            try {
                RcaReport.RcaStatus rcaStatus = RcaReport.RcaStatus.valueOf(status.toUpperCase());
                return ResponseEntity.ok(rcaRepository.findByStatus(rcaStatus));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(rcaRepository.findAllByOrderByGeneratedAtDesc());
    }

    /**
     * Get a single RCA by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<RcaReport> getRca(@PathVariable String id) {
        return rcaRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the RCA for a specific incident.
     */
    @GetMapping("/incident/{incidentId}")
    public ResponseEntity<RcaReport> getRcaForIncident(@PathVariable String incidentId) {
        return rcaService.getRcaForIncident(incidentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update/edit RCA fields (human edits during review).
     */
    @PutMapping("/{id}")
    public ResponseEntity<RcaReport> updateRca(@PathVariable String id,
                                                @RequestBody Map<String, String> updates) {
        return rcaRepository.findById(id)
                .map(rca -> {
                    if (updates.containsKey("summary")) rca.setSummary(updates.get("summary"));
                    if (updates.containsKey("timeline")) rca.setTimeline(updates.get("timeline"));
                    if (updates.containsKey("rootCause")) rca.setRootCause(updates.get("rootCause"));
                    if (updates.containsKey("impact")) rca.setImpact(updates.get("impact"));
                    if (updates.containsKey("resolution")) rca.setResolution(updates.get("resolution"));
                    if (updates.containsKey("lessonsLearned")) rca.setLessonsLearned(updates.get("lessonsLearned"));
                    if (updates.containsKey("preventiveMeasures")) rca.setPreventiveMeasures(updates.get("preventiveMeasures"));
                    if (updates.containsKey("reviewNotes")) rca.setReviewNotes(updates.get("reviewNotes"));
                    return ResponseEntity.ok(rcaRepository.save(rca));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Approve an RCA (with optional edits in the body).
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveRca(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String reviewedBy = body != null ? body.getOrDefault("reviewed_by", "user") : "user";
            RcaReport approved = rcaService.approveRca(id, reviewedBy, body);
            return ResponseEntity.ok(Map.of(
                    "id", approved.getId(),
                    "status", approved.getStatus().name(),
                    "message", "RCA approved successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reject an RCA, optionally regenerating it.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectRca(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String reviewedBy = body != null ? body.getOrDefault("reviewed_by", "user") : "user";
            String reason = body != null ? body.getOrDefault("reason", "") : "";
            boolean regenerate = body != null && "true".equals(body.get("regenerate"));

            Incident incident = null;
            if (regenerate) {
                RcaReport rca = rcaRepository.findById(id).orElse(null);
                if (rca != null) {
                    incident = incidentRepository.findById(rca.getIncidentId()).orElse(null);
                }
            }

            RcaReport rejected = rcaService.rejectRca(id, reviewedBy, reason, regenerate, incident);
            return ResponseEntity.ok(Map.of(
                    "id", rejected.getId(),
                    "status", rejected.getStatus().name(),
                    "message", regenerate ? "RCA rejected and regeneration triggered" : "RCA rejected"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manually trigger RCA generation for a resolved incident.
     */
    @PostMapping("/generate/{incidentId}")
    public ResponseEntity<Map<String, Object>> generateRca(@PathVariable String incidentId) {
        var optIncident = incidentRepository.findById(incidentId);
        if (optIncident.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Incident incident = optIncident.get();
        if (incident.getStatus() != Incident.IncidentStatus.RESOLVED
                && incident.getStatus() != Incident.IncidentStatus.CLOSED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Incident must be resolved or closed to generate an RCA"
            ));
        }
        rcaService.generateRca(incident);
        return ResponseEntity.ok(Map.of(
                "message", "RCA generation started for incident " + incidentId,
                "incident_id", incidentId
        ));
    }

    /**
     * Get count of RCAs pending review (for badge).
     */
    @GetMapping("/pending/count")
    public ResponseEntity<Map<String, Object>> getPendingCount() {
        return ResponseEntity.ok(Map.of("count", rcaService.getPendingReviewCount()));
    }

    // ─── Executive Summary ───

    @GetMapping("/executive-summary")
    public ResponseEntity<Map<String, Object>> getRcaExecutiveSummary() {
        return ResponseEntity.ok(rcaService.getRcaExecutiveSummary());
    }

    @PostMapping("/executive-summary/refresh")
    public ResponseEntity<Map<String, Object>> refreshRcaExecutiveSummary() {
        return ResponseEntity.ok(rcaService.refreshRcaExecutiveSummary());
    }
}
