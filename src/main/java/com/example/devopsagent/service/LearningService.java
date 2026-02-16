package com.example.devopsagent.service;

import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.embedding.EmbeddingService;
import com.example.devopsagent.embedding.VectorStore;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Learning Service - Tracks which tool sequences resolve incidents successfully
 * and recommends approaches for similar future incidents.
 */
@Slf4j
@Service
public class LearningService {

    private final ResolutionRecordRepository resolutionRepository;
    private final IncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public LearningService(ResolutionRecordRepository resolutionRepository,
                           IncidentRepository incidentRepository,
                           ObjectMapper objectMapper,
                           AuditService auditService,
                           @Lazy EmbeddingService embeddingService,
                           @Lazy VectorStore vectorStore) {
        this.resolutionRepository = resolutionRepository;
        this.incidentRepository = incidentRepository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * Record a resolution attempt (called when an incident is resolved via chat or playbook).
     */
    public void recordResolution(String incidentId, String service, String title,
                                  List<String> toolsUsed, boolean success, long resolutionTimeMs) {
        try {
            String toolSequenceJson = objectMapper.writeValueAsString(toolsUsed);
            ResolutionRecord record = ResolutionRecord.builder()
                    .incidentId(incidentId)
                    .service(service)
                    .incidentTitle(title)
                    .toolSequence(toolSequenceJson)
                    .success(success)
                    .resolutionTimeMs(resolutionTimeMs)
                    .createdAt(Instant.now())
                    .build();
            resolutionRepository.save(record);

            log.info("Recorded resolution for service '{}': {} tools, success={}, {}ms",
                    service, toolsUsed.size(), success, resolutionTimeMs);

            auditService.log("system", "RESOLUTION_RECORDED", service,
                    Map.of("incident_id", incidentId != null ? incidentId : "",
                           "tools", toolsUsed, "success", success));

            // Embed the resolution record for semantic similarity search
            embeddingService.embedResolutionAsync(record);
        } catch (Exception e) {
            log.error("Failed to record resolution: {}", e.getMessage());
        }
    }

    /**
     * Get recommended approach for a service based on past successful resolutions.
     */
    public String getRecommendedApproach(String service) {
        List<ResolutionRecord> successful = resolutionRepository.findSuccessfulByService(service);
        if (successful.isEmpty()) return null;

        // Find the most common tool sequences
        Map<String, Long> sequenceFrequency = successful.stream()
                .collect(Collectors.groupingBy(ResolutionRecord::getToolSequence, Collectors.counting()));

        // Get the most frequent successful sequence
        Optional<Map.Entry<String, Long>> mostCommon = sequenceFrequency.entrySet().stream()
                .max(Map.Entry.comparingByValue());

        if (mostCommon.isEmpty()) return null;

        try {
            List<String> tools = objectMapper.readValue(mostCommon.get().getKey(), List.class);
            long count = mostCommon.get().getValue();
            return String.format("For service '%s', the following approach has resolved issues %d time(s): %s",
                    service, count, String.join(" -> ", tools));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get success rate for a service (percentage of incidents auto-resolved).
     */
    public double getSuccessRate(String service) {
        long total = resolutionRepository.countByService(service);
        if (total == 0) return 0.0;
        long successful = resolutionRepository.countSuccessfulByService(service);
        return (double) successful / total * 100.0;
    }

    /**
     * Get resolution insights for the dashboard.
     */
    public Map<String, Object> getInsights() {
        List<ResolutionRecord> allRecords = resolutionRepository.findAll();
        if (allRecords.isEmpty()) {
            return Map.of("total_resolutions", 0, "patterns", List.of(), "service_stats", List.of());
        }

        long totalSuccess = allRecords.stream().filter(ResolutionRecord::isSuccess).count();

        // Top resolution patterns (most common tool sequences)
        Map<String, Long> patterns = allRecords.stream()
                .filter(ResolutionRecord::isSuccess)
                .collect(Collectors.groupingBy(ResolutionRecord::getToolSequence, Collectors.counting()));
        List<Map<String, Object>> topPatterns = patterns.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> Map.<String, Object>of("sequence", e.getKey(), "count", e.getValue()))
                .toList();

        // Per-service stats
        Map<String, List<ResolutionRecord>> byService = allRecords.stream()
                .filter(r -> r.getService() != null)
                .collect(Collectors.groupingBy(ResolutionRecord::getService));
        List<Map<String, Object>> serviceStats = byService.entrySet().stream()
                .map(e -> {
                    long svcTotal = e.getValue().size();
                    long svcSuccess = e.getValue().stream().filter(ResolutionRecord::isSuccess).count();
                    double avgTime = e.getValue().stream()
                            .filter(ResolutionRecord::isSuccess)
                            .mapToLong(ResolutionRecord::getResolutionTimeMs)
                            .average().orElse(0);
                    return Map.<String, Object>of(
                            "service", e.getKey(),
                            "total", svcTotal,
                            "successful", svcSuccess,
                            "success_rate", svcTotal > 0 ? (double) svcSuccess / svcTotal * 100 : 0,
                            "avg_resolution_ms", (long) avgTime);
                })
                .toList();

        return Map.of(
                "total_resolutions", allRecords.size(),
                "total_successful", totalSuccess,
                "overall_success_rate", allRecords.size() > 0 ? (double) totalSuccess / allRecords.size() * 100 : 0,
                "patterns", topPatterns,
                "service_stats", serviceStats
        );
    }

    /**
     * Semantic recommendation: embed the problem description and find the most
     * successful tool sequences from semantically similar past resolutions.
     * Enables cross-service pattern transfer (e.g. MySQL timeout informs Postgres timeout).
     *
     * @return human-readable recommendation string, or null if nothing found
     */
    public String getSemanticRecommendation(String problemDescription) {
        if (!embeddingService.isEnabled() || problemDescription == null || problemDescription.isBlank()) {
            return null;
        }

        try {
            float[] queryVec = embeddingService.embed(problemDescription);
            if (queryVec == null) return null;

            List<VectorStore.ScoredResult> similar = vectorStore.findSimilar(queryVec, "resolution", 5, 0.65);
            if (similar.isEmpty()) return null;

            // Collect the successful tool sequences from the top matches
            StringBuilder sb = new StringBuilder("Semantically similar past resolutions:\n");
            int count = 0;
            for (VectorStore.ScoredResult result : similar) {
                Optional<ResolutionRecord> record = resolutionRepository.findById(result.entityId());
                if (record.isPresent() && record.get().isSuccess()) {
                    ResolutionRecord r = record.get();
                    try {
                        List<String> tools = objectMapper.readValue(r.getToolSequence(), List.class);
                        sb.append(String.format("  - [%.0f%% match] Service '%s': %s (%dms)\n",
                                result.score() * 100,
                                r.getService() != null ? r.getService() : "unknown",
                                String.join(" -> ", tools),
                                r.getResolutionTimeMs()));
                        count++;
                    } catch (Exception ignored) {}
                }
            }

            return count > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("Semantic recommendation failed: {}", e.getMessage());
            return null;
        }
    }
}
