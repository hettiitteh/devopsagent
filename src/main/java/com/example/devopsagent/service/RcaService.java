package com.example.devopsagent.service;

import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.agent.LlmClient;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.RcaReport;
import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.embedding.EmbeddingService;
import com.example.devopsagent.embedding.VectorStore;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.memory.IncidentKnowledgeBase;
import com.example.devopsagent.repository.RcaReportRepository;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for generating, managing, and reviewing Root Cause Analysis reports.
 * Uses the LLM to produce comprehensive RCAs after incident resolution,
 * then sends them for human review and approval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RcaService {

    private final RcaReportRepository rcaRepository;
    private final ResolutionRecordRepository resolutionRepository;
    private final IncidentKnowledgeBase knowledgeBase;
    private final LlmClient llmClient;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    private volatile String cachedRcaSummary;
    private volatile Instant cachedRcaSummaryAt;

    /**
     * Asynchronously generate a comprehensive RCA for a resolved incident.
     * 1. Save a placeholder with GENERATING status
     * 2. Gather all context (incident, resolution records, similar incidents)
     * 3. Call the LLM with a structured prompt
     * 4. Parse the response and update the RCA
     * 5. Broadcast to the UI for human review
     */
    @Async
    public void generateRca(Incident incident) {
        log.info("Generating RCA for incident {} ({})", incident.getId(), incident.getTitle());

        // Check if RCA already exists for this incident
        Optional<RcaReport> existing = rcaRepository.findByIncidentId(incident.getId());
        if (existing.isPresent() && existing.get().getStatus() != RcaReport.RcaStatus.REJECTED) {
            log.info("RCA already exists for incident {} with status {}", incident.getId(), existing.get().getStatus());
            return;
        }

        // Calculate resolution time
        long resolutionTimeMs = 0;
        if (incident.getCreatedAt() != null && incident.getResolvedAt() != null) {
            resolutionTimeMs = incident.getResolvedAt().toEpochMilli() - incident.getCreatedAt().toEpochMilli();
        }

        // 1. Save placeholder
        RcaReport rca = RcaReport.builder()
                .incidentId(incident.getId())
                .service(incident.getService())
                .incidentTitle(incident.getTitle())
                .severity(incident.getSeverity() != null ? incident.getSeverity().name() : "UNKNOWN")
                .resolutionTimeMs(resolutionTimeMs)
                .status(RcaReport.RcaStatus.GENERATING)
                .generatedAt(Instant.now())
                .build();
        rca = rcaRepository.save(rca);

        try {
            // 2. Gather context
            String prompt = buildRcaPrompt(incident, resolutionTimeMs);

            // 3. Call LLM
            List<AgentMessage> messages = List.of(
                    AgentMessage.system(prompt),
                    AgentMessage.user("Generate the RCA report now.")
            );
            AgentMessage response = llmClient.chat(messages, List.of());
            String content = response.getContent();

            if (content == null || content.isBlank()) {
                log.warn("LLM returned empty RCA for incident {}", incident.getId());
                rca.setStatus(RcaReport.RcaStatus.PENDING_REVIEW);
                rca.setSummary("RCA generation returned empty content. Please write manually.");
                rcaRepository.save(rca);
                broadcastRcaGenerated(rca);
                return;
            }

            // 4. Parse JSON response
            parseAndPopulateRca(rca, content, incident);
            rca.setStatus(RcaReport.RcaStatus.PENDING_REVIEW);
            rcaRepository.save(rca);

            // 5. Embed the RCA for future similarity search
            embeddingService.embedRcaAsync(rca);

            // 6. Broadcast for review
            broadcastRcaGenerated(rca);

            auditService.log("system", "RCA_GENERATED", rca.getId(),
                    Map.of("incident_id", incident.getId(),
                            "service", incident.getService() != null ? incident.getService() : "unknown"));

            log.info("RCA generated successfully for incident {} -- pending review (id: {})",
                    incident.getId(), rca.getId());

        } catch (Exception e) {
            log.error("Failed to generate RCA for incident {}: {}", incident.getId(), e.getMessage(), e);
            // Save what we can -- mark as pending review so user can fill in manually
            rca.setStatus(RcaReport.RcaStatus.PENDING_REVIEW);
            rca.setSummary("RCA auto-generation failed: " + e.getMessage() + ". Please write the RCA manually.");
            rcaRepository.save(rca);
            broadcastRcaGenerated(rca);
        }
    }

    /**
     * Approve an RCA with optional edits from the reviewer.
     */
    public RcaReport approveRca(String rcaId, String reviewedBy, Map<String, String> updates) {
        RcaReport rca = rcaRepository.findById(rcaId)
                .orElseThrow(() -> new IllegalArgumentException("RCA not found: " + rcaId));

        // Apply any edits
        if (updates != null) {
            if (updates.containsKey("summary")) rca.setSummary(updates.get("summary"));
            if (updates.containsKey("timeline")) rca.setTimeline(updates.get("timeline"));
            if (updates.containsKey("rootCause")) rca.setRootCause(updates.get("rootCause"));
            if (updates.containsKey("impact")) rca.setImpact(updates.get("impact"));
            if (updates.containsKey("resolution")) rca.setResolution(updates.get("resolution"));
            if (updates.containsKey("lessonsLearned")) rca.setLessonsLearned(updates.get("lessonsLearned"));
            if (updates.containsKey("preventiveMeasures")) rca.setPreventiveMeasures(updates.get("preventiveMeasures"));
            if (updates.containsKey("reviewNotes")) rca.setReviewNotes(updates.get("reviewNotes"));
        }

        rca.setStatus(RcaReport.RcaStatus.APPROVED);
        rca.setReviewedAt(Instant.now());
        rca.setReviewedBy(reviewedBy);
        rcaRepository.save(rca);

        auditService.log(reviewedBy != null ? reviewedBy : "user", "RCA_APPROVED", rcaId,
                Map.of("incident_id", rca.getIncidentId()));

        gatewayHandler.broadcast("rca.approved", Map.of(
                "rca_id", rca.getId(),
                "incident_id", rca.getIncidentId(),
                "service", rca.getService() != null ? rca.getService() : "unknown"
        ));

        return rca;
    }

    /**
     * Reject an RCA, optionally regenerating it.
     */
    public RcaReport rejectRca(String rcaId, String reviewedBy, String reason, boolean regenerate,
                                Incident incident) {
        RcaReport rca = rcaRepository.findById(rcaId)
                .orElseThrow(() -> new IllegalArgumentException("RCA not found: " + rcaId));

        rca.setStatus(RcaReport.RcaStatus.REJECTED);
        rca.setReviewedAt(Instant.now());
        rca.setReviewedBy(reviewedBy);
        rca.setReviewNotes(reason);
        rcaRepository.save(rca);

        auditService.log(reviewedBy != null ? reviewedBy : "user", "RCA_REJECTED", rcaId,
                Map.of("incident_id", rca.getIncidentId(), "reason", reason != null ? reason : ""));

        if (regenerate && incident != null) {
            generateRca(incident);
        }

        return rca;
    }

    /**
     * Get the RCA for a specific incident.
     */
    public Optional<RcaReport> getRcaForIncident(String incidentId) {
        return rcaRepository.findByIncidentId(incidentId);
    }

    /**
     * Get the count of RCAs pending review.
     */
    public long getPendingReviewCount() {
        return rcaRepository.countByStatus(RcaReport.RcaStatus.PENDING_REVIEW);
    }

    // ─── Executive Summary ───

    /**
     * Get the RCA executive summary (returns cached version if available).
     */
    public Map<String, Object> getRcaExecutiveSummary() {
        if (cachedRcaSummary != null && cachedRcaSummaryAt != null) {
            return Map.of("summary", cachedRcaSummary, "generatedAt", cachedRcaSummaryAt.toString());
        }
        return refreshRcaExecutiveSummary();
    }

    /**
     * Force-refresh the RCA executive summary by calling the LLM with all RCA data.
     */
    public Map<String, Object> refreshRcaExecutiveSummary() {
        try {
            List<RcaReport> allRcas = rcaRepository.findAllByOrderByGeneratedAtDesc();

            if (allRcas.isEmpty()) {
                cachedRcaSummary = "No RCA reports generated yet. RCA reports are automatically created when incidents are resolved.";
                cachedRcaSummaryAt = Instant.now();
                return Map.of("summary", cachedRcaSummary, "generatedAt", cachedRcaSummaryAt.toString());
            }

            String prompt = buildRcaSummaryPrompt(allRcas);

            List<AgentMessage> messages = List.of(
                    AgentMessage.system(prompt),
                    AgentMessage.user("Generate the RCA executive summary now.")
            );
            AgentMessage response = llmClient.chat(messages, List.of());
            String content = response.getContent();

            if (content != null && !content.isBlank()) {
                cachedRcaSummary = content.trim();
            } else {
                cachedRcaSummary = "Summary generation returned empty content. Please try again.";
            }
            cachedRcaSummaryAt = Instant.now();

            log.info("RCA executive summary refreshed");
            return Map.of("summary", cachedRcaSummary, "generatedAt", cachedRcaSummaryAt.toString());

        } catch (Exception e) {
            log.error("Failed to generate RCA executive summary: {}", e.getMessage());
            String fallback = cachedRcaSummary != null ? cachedRcaSummary : "Failed to generate summary: " + e.getMessage();
            Instant ts = cachedRcaSummaryAt != null ? cachedRcaSummaryAt : Instant.now();
            return Map.of("summary", fallback, "generatedAt", ts.toString());
        }
    }

    private String buildRcaSummaryPrompt(List<RcaReport> allRcas) {
        long pending = allRcas.stream().filter(r -> r.getStatus() == RcaReport.RcaStatus.PENDING_REVIEW).count();
        long approved = allRcas.stream().filter(r -> r.getStatus() == RcaReport.RcaStatus.APPROVED).count();
        long rejected = allRcas.stream().filter(r -> r.getStatus() == RcaReport.RcaStatus.REJECTED).count();

        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an expert SRE analyst. Based on the Root Cause Analysis reports below, produce \
                a concise executive summary (3-5 paragraphs) covering:
                1. Recurring root causes across services and their frequency
                2. Most impactful incidents and their systemic implications
                3. Common lessons learned and organizational takeaways
                4. Top preventive measures that should be prioritized
                
                Write in a professional, analytical tone. Do not use JSON -- write natural prose.
                
                """);

        sb.append("## RCA Report Statistics\n");
        sb.append(String.format("- Total RCA reports: %d\n", allRcas.size()));
        sb.append(String.format("- Pending review: %d\n", pending));
        sb.append(String.format("- Approved: %d\n", approved));
        sb.append(String.format("- Rejected: %d\n", rejected));
        sb.append("\n");

        // Include up to 20 most recent RCAs with details
        List<RcaReport> rcasToInclude = allRcas.stream().limit(20).toList();
        sb.append("## RCA Reports (newest first)\n");
        for (RcaReport r : rcasToInclude) {
            sb.append(String.format("\n### %s [%s] — %s\n",
                    r.getIncidentTitle() != null ? r.getIncidentTitle() : "Untitled",
                    r.getSeverity() != null ? r.getSeverity() : "?",
                    r.getStatus().name()));
            sb.append(String.format("- Service: %s\n", r.getService() != null ? r.getService() : "unknown"));
            sb.append(String.format("- Resolution time: %s\n", formatDuration(r.getResolutionTimeMs())));
            if (r.getSummary() != null) {
                sb.append(String.format("- Summary: %s\n", truncate(r.getSummary(), 300)));
            }
            if (r.getRootCause() != null) {
                sb.append(String.format("- Root Cause: %s\n", truncate(r.getRootCause(), 300)));
            }
            if (r.getLessonsLearned() != null) {
                sb.append(String.format("- Lessons Learned: %s\n", truncate(r.getLessonsLearned(), 300)));
            }
            if (r.getPreventiveMeasures() != null) {
                sb.append(String.format("- Preventive Measures: %s\n", truncate(r.getPreventiveMeasures(), 300)));
            }
        }

        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ─── Private helpers ───

    private String buildRcaPrompt(Incident incident, long resolutionTimeMs) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                You are an expert SRE writing a comprehensive Root Cause Analysis (RCA) report \
                for a production incident. Your RCA should be thorough, actionable, and suitable \
                for a post-incident review meeting.
                
                """);

        // Incident details
        prompt.append("## Incident Details\n");
        prompt.append(String.format("- Title: %s\n", incident.getTitle()));
        prompt.append(String.format("- Service: %s\n", incident.getService() != null ? incident.getService() : "unknown"));
        prompt.append(String.format("- Severity: %s\n", incident.getSeverity()));
        prompt.append(String.format("- Created: %s\n", incident.getCreatedAt()));
        prompt.append(String.format("- Resolved: %s\n", incident.getResolvedAt()));
        prompt.append(String.format("- Resolution time: %s\n", formatDuration(resolutionTimeMs)));
        if (incident.getDescription() != null) {
            prompt.append(String.format("- Description: %s\n", incident.getDescription()));
        }
        if (incident.getResolution() != null) {
            prompt.append(String.format("- Resolution notes: %s\n", incident.getResolution()));
        }
        if (incident.getRootCause() != null) {
            prompt.append(String.format("- Known root cause: %s\n", incident.getRootCause()));
        }
        if (incident.getPlaybookId() != null) {
            prompt.append(String.format("- Playbook executed: %s\n", incident.getPlaybookId()));
        }
        if (incident.getMetadata() != null) {
            prompt.append(String.format("- Additional metadata: %s\n", incident.getMetadata()));
        }
        prompt.append("\n");

        // Resolution records from learning system
        List<ResolutionRecord> records = resolutionRepository.findByIncidentId(incident.getId());
        if (!records.isEmpty()) {
            prompt.append("## Resolution Data\n");
            for (ResolutionRecord r : records) {
                prompt.append(String.format("- Tools used: %s\n", r.getToolSequence()));
                prompt.append(String.format("- Success: %s\n", r.isSuccess()));
                prompt.append(String.format("- Resolution time: %dms\n", r.getResolutionTimeMs()));
            }
            prompt.append("\n");
        }

        // Similar past incidents for pattern recognition
        try {
            List<Incident> similar = knowledgeBase.searchSimilarIncidents(
                    incident.getTitle(), incident.getService());
            List<Incident> pastResolved = similar.stream()
                    .filter(i -> !i.getId().equals(incident.getId()))
                    .filter(i -> i.getStatus() == Incident.IncidentStatus.RESOLVED
                            || i.getStatus() == Incident.IncidentStatus.CLOSED)
                    .limit(3)
                    .toList();

            if (!pastResolved.isEmpty()) {
                prompt.append("## Similar Past Incidents\n");
                for (Incident past : pastResolved) {
                    prompt.append(String.format("- [%s] %s -- root cause: %s, resolution: %s\n",
                            past.getSeverity(),
                            past.getTitle(),
                            past.getRootCause() != null ? past.getRootCause() : "unknown",
                            past.getResolution() != null ? past.getResolution() : "unknown"));
                }
                prompt.append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to fetch similar incidents for RCA: {}", e.getMessage());
        }

        // Similar past RCA reports (embedding-based) for deeper pattern recognition
        try {
            if (embeddingService.isEnabled()) {
                String queryText = incident.getTitle() + " " +
                        (incident.getDescription() != null ? incident.getDescription() : "");
                float[] queryVec = embeddingService.embed(queryText);
                if (queryVec != null) {
                    List<VectorStore.ScoredResult> similarRcas = vectorStore.findSimilar(queryVec, "rca", 3, 0.7);
                    if (!similarRcas.isEmpty()) {
                        prompt.append("## Similar Past RCA Reports\n");
                        for (VectorStore.ScoredResult result : similarRcas) {
                            Optional<RcaReport> pastRca = rcaRepository.findById(result.entityId());
                            if (pastRca.isPresent()) {
                                RcaReport r = pastRca.get();
                                prompt.append(String.format("- [%.0f%% match] %s — Root cause: %s, Lessons: %s\n",
                                        result.score() * 100,
                                        r.getIncidentTitle() != null ? r.getIncidentTitle() : "unknown",
                                        r.getRootCause() != null ? r.getRootCause().substring(0, Math.min(200, r.getRootCause().length())) : "unknown",
                                        r.getLessonsLearned() != null ? r.getLessonsLearned().substring(0, Math.min(200, r.getLessonsLearned().length())) : "unknown"));
                            }
                        }
                        prompt.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch similar RCAs for prompt: {}", e.getMessage());
        }

        // Task
        prompt.append("""
                ## Task
                Generate a comprehensive Root Cause Analysis with these exact JSON fields:
                - "summary": A concise executive summary (2-3 sentences) of what happened and why
                - "timeline": A chronological timeline of events from detection to resolution, \
                with timestamps where available
                - "rootCause": The identified root cause with technical details
                - "impact": The scope of impact -- which users, services, or business functions \
                were affected and how
                - "resolution": Detailed steps taken to resolve the incident
                - "lessonsLearned": Key takeaways and what the team learned from this incident
                - "preventiveMeasures": Specific, actionable items to prevent recurrence \
                (e.g., add monitoring, update runbook, implement circuit breaker)
                
                Respond ONLY with a JSON object containing these fields. No markdown, no explanation \
                outside the JSON.
                """);

        return prompt.toString();
    }

    private void parseAndPopulateRca(RcaReport rca, String content, Incident incident) {
        try {
            // Strip markdown code fences if present
            content = content.trim();
            if (content.startsWith("```")) {
                content = content.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }

            JsonNode node = objectMapper.readTree(content);

            rca.setSummary(getJsonField(node, "summary",
                    "Incident " + incident.getTitle() + " was resolved."));
            rca.setTimeline(getJsonField(node, "timeline", "Timeline not available."));
            rca.setRootCause(getJsonField(node, "rootCause", "Root cause pending analysis."));
            rca.setImpact(getJsonField(node, "impact", "Impact assessment pending."));
            rca.setResolution(getJsonField(node, "resolution",
                    incident.getResolution() != null ? incident.getResolution() : "Resolution details pending."));
            rca.setLessonsLearned(getJsonField(node, "lessonsLearned", "Lessons learned pending review."));
            rca.setPreventiveMeasures(getJsonField(node, "preventiveMeasures",
                    "Preventive measures pending review."));

            // Capture tools used from resolution records
            List<ResolutionRecord> records = resolutionRepository.findByIncidentId(incident.getId());
            if (!records.isEmpty()) {
                rca.setToolsUsed(records.get(0).getToolSequence());
            }

        } catch (Exception e) {
            log.warn("Failed to parse LLM RCA response as JSON, using raw content: {}", e.getMessage());
            // Fallback: put the entire response in summary
            rca.setSummary(content.length() > 2000 ? content.substring(0, 2000) : content);
            rca.setRootCause("See summary -- LLM response could not be parsed as structured JSON.");
        }
    }

    private String getJsonField(JsonNode node, String field, String defaultValue) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return defaultValue;
    }

    private void broadcastRcaGenerated(RcaReport rca) {
        gatewayHandler.broadcast("rca.generated", Map.of(
                "rca_id", rca.getId(),
                "incident_id", rca.getIncidentId(),
                "incident_title", rca.getIncidentTitle() != null ? rca.getIncidentTitle() : "Unknown",
                "service", rca.getService() != null ? rca.getService() : "unknown",
                "status", rca.getStatus().name()
        ));
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "unknown";
        Duration d = Duration.ofMillis(ms);
        if (d.toHours() > 0) {
            return String.format("%dh %dm %ds", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
        } else if (d.toMinutes() > 0) {
            return String.format("%dm %ds", d.toMinutes(), d.toSecondsPart());
        } else {
            return String.format("%ds", d.toSeconds());
        }
    }
}
