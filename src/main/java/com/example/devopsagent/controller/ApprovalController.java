package com.example.devopsagent.controller;

import com.example.devopsagent.domain.ApprovalRequest;
import com.example.devopsagent.service.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Approval Workflow REST API Controller.
 */
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    /**
     * List pending approval requests.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalRequest>> getPending() {
        return ResponseEntity.ok(approvalService.getPending());
    }

    /**
     * Respond to an approval request (approve or deny).
     */
    @PostMapping("/{id}/respond")
    public ResponseEntity<ApprovalRequest> respond(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String respondedBy = (String) body.getOrDefault("respondedBy", "user");
        return ResponseEntity.ok(approvalService.respond(id, approved, respondedBy));
    }

    /**
     * Get approvals for a session.
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<ApprovalRequest>> getBySession(@PathVariable String sessionId) {
        return ResponseEntity.ok(approvalService.getBySession(sessionId));
    }
}
