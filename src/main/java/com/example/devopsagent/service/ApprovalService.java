package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentEngine;
import com.example.devopsagent.domain.ApprovalRequest;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.repository.ApprovalRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Manages approval workflows for tools that require human confirmation.
 */
@Slf4j
@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvalRepository;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AgentEngine agentEngine;

    public ApprovalService(ApprovalRequestRepository approvalRepository,
                           GatewayWebSocketHandler gatewayHandler,
                           AuditService auditService,
                           ObjectMapper objectMapper,
                           @Lazy AgentEngine agentEngine) {
        this.approvalRepository = approvalRepository;
        this.gatewayHandler = gatewayHandler;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.agentEngine = agentEngine;
    }

    /**
     * Create an approval request and broadcast it.
     * Deduplicates: if there's already a PENDING request for the same tool+session, returns it.
     */
    public ApprovalRequest requestApproval(String toolName, Map<String, Object> parameters,
                                            String sessionId, String incidentId) {
        try {
            // Dedup: check for existing PENDING request for same tool + session
            if (sessionId != null) {
                List<ApprovalRequest> existing = approvalRepository.findBySessionIdAndStatus(
                        sessionId, ApprovalRequest.ApprovalStatus.PENDING);
                for (ApprovalRequest ex : existing) {
                    if (toolName.equals(ex.getToolName())) {
                        log.debug("Approval already pending for tool '{}' in session {}, reusing id={}",
                                toolName, sessionId, ex.getId());
                        return ex;
                    }
                }
            }

            String paramsJson = objectMapper.writeValueAsString(parameters);
            ApprovalRequest request = ApprovalRequest.builder()
                    .toolName(toolName)
                    .parameters(paramsJson)
                    .sessionId(sessionId)
                    .incidentId(incidentId)
                    .status(ApprovalRequest.ApprovalStatus.PENDING)
                    .requestedAt(Instant.now())
                    .build();
            request = approvalRepository.save(request);

            log.info("Approval requested for tool '{}' in session {} (id: {})",
                    toolName, sessionId, request.getId());

            // Broadcast via WebSocket
            gatewayHandler.broadcast("approval.requested", Map.of(
                    "approval_id", request.getId(),
                    "tool_name", toolName,
                    "session_id", sessionId != null ? sessionId : "",
                    "incident_id", incidentId != null ? incidentId : "",
                    "parameters", paramsJson
            ));

            // Audit
            auditService.log("system", "APPROVAL_REQUESTED", toolName,
                    Map.of("approval_id", request.getId(), "session_id", sessionId != null ? sessionId : ""),
                    sessionId, true);

            return request;
        } catch (Exception e) {
            log.error("Failed to create approval request: {}", e.getMessage());
            throw new RuntimeException("Failed to create approval request", e);
        }
    }

    /**
     * Respond to an approval request (approve or deny).
     */
    public ApprovalRequest respond(String approvalId, boolean approved, String respondedBy) {
        ApprovalRequest request = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (request.getStatus() != ApprovalRequest.ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval already responded to: " + request.getStatus());
        }

        request.setStatus(approved ? ApprovalRequest.ApprovalStatus.APPROVED : ApprovalRequest.ApprovalStatus.DENIED);
        request.setRespondedAt(Instant.now());
        request.setRespondedBy(respondedBy);
        approvalRepository.save(request);

        String action = approved ? "APPROVAL_GRANTED" : "APPROVAL_DENIED";
        log.info("{} for tool '{}' by {} (id: {})", action, request.getToolName(), respondedBy, approvalId);

        // Broadcast response
        gatewayHandler.broadcast("approval.responded", Map.of(
                "approval_id", approvalId,
                "tool_name", request.getToolName(),
                "approved", approved,
                "responded_by", respondedBy != null ? respondedBy : "unknown",
                "session_id", request.getSessionId() != null ? request.getSessionId() : ""
        ));

        // Audit
        auditService.log(respondedBy != null ? respondedBy : "user", action, request.getToolName(),
                Map.of("approval_id", approvalId), request.getSessionId(), true);

        // If approved and there's an active session, auto-resume the agent loop
        if (approved && request.getSessionId() != null) {
            try {
                log.info("Auto-resuming agent session {} after approval of '{}'",
                        request.getSessionId(), request.getToolName());
                agentEngine.resumeAfterApproval(request.getSessionId(), request.getToolName())
                        .thenAccept(agentResponse -> {
                            // Push the resumed agent's response to the UI via WebSocket
                            if (agentResponse != null && agentResponse.getResponse() != null) {
                                gatewayHandler.broadcast("agent.response", Map.of(
                                        "session_id", agentResponse.getSessionId(),
                                        "response", agentResponse.getResponse(),
                                        "tools_used", agentResponse.getToolsUsed() != null ? agentResponse.getToolsUsed() : List.of(),
                                        "iterations", agentResponse.getIterations()
                                ));
                            }
                        })
                        .exceptionally(ex -> {
                            log.warn("Resume agent response broadcast failed: {}", ex.getMessage());
                            return null;
                        });
            } catch (Exception e) {
                log.warn("Failed to auto-resume session {} after approval: {}",
                        request.getSessionId(), e.getMessage());
            }
        }

        return request;
    }

    /** Get all pending approval requests */
    public List<ApprovalRequest> getPending() {
        return approvalRepository.findByStatus(ApprovalRequest.ApprovalStatus.PENDING);
    }

    /** Get approvals for a specific session */
    public List<ApprovalRequest> getBySession(String sessionId) {
        return approvalRepository.findBySessionId(sessionId);
    }
}
