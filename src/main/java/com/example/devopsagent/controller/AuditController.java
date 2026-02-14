package com.example.devopsagent.controller;

import com.example.devopsagent.domain.AuditLog;
import com.example.devopsagent.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Audit Trail REST API Controller.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<AuditLog>> getAuditLogs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @RequestParam(defaultValue = "100") int limit) {
        if (actor == null && action == null && target == null) {
            return ResponseEntity.ok(auditService.getRecent(limit));
        }
        return ResponseEntity.ok(auditService.filter(actor, action, target));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<AuditLog>> getBySession(@PathVariable String sessionId) {
        return ResponseEntity.ok(auditService.getBySession(sessionId));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "total_tool_executions", auditService.countByAction("TOOL_EXECUTED"),
                "total_incidents_created", auditService.countByAction("INCIDENT_CREATED"),
                "total_playbook_runs", auditService.countByAction("PLAYBOOK_RUN"),
                "total_escalations", auditService.countByAction("ESCALATION"),
                "total_approvals", auditService.countByAction("APPROVAL_GRANTED")
        ));
    }
}
