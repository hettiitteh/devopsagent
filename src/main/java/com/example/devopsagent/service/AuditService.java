package com.example.devopsagent.service;

import com.example.devopsagent.domain.AuditLog;
import com.example.devopsagent.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Central audit logging service. All significant actions in the system
 * should be recorded through this service for compliance and traceability.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Log an action to the audit trail (async to avoid blocking callers).
     */
    @Async("agentExecutor")
    public void log(String actor, String action, String target, Map<String, Object> details) {
        log(actor, action, target, details, null, true);
    }

    @Async("agentExecutor")
    public void log(String actor, String action, String target, Map<String, Object> details,
                    String sessionId, boolean success) {
        try {
            String detailsJson = details != null ? objectMapper.writeValueAsString(details) : null;
            AuditLog entry = AuditLog.builder()
                    .actor(actor)
                    .action(action)
                    .target(target)
                    .details(detailsJson)
                    .sessionId(sessionId)
                    .success(success)
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(entry);
            log.debug("Audit: [{}] {} -> {} ({})", actor, action, target, success ? "OK" : "FAIL");
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage());
        }
    }

    /** Convenience: log a successful action without details */
    public void log(String actor, String action, String target) {
        log(actor, action, target, null, null, true);
    }

    /** Get recent audit entries */
    public List<AuditLog> getRecent(int limit) {
        return auditLogRepository.findAllPaged(
                org.springframework.data.domain.PageRequest.of(0, limit)).getContent();
    }

    /** Filter audit entries */
    public List<AuditLog> filter(String actor, String action, String target) {
        return auditLogRepository.findFiltered(actor, action, target);
    }

    /** Get entries for a specific session */
    public List<AuditLog> getBySession(String sessionId) {
        return auditLogRepository.findBySessionIdOrderByTimestampDesc(sessionId);
    }

    /** Count entries by action type */
    public long countByAction(String action) {
        return auditLogRepository.countByAction(action);
    }
}
