package com.example.devopsagent.memory;

import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.service.LearningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Incident Knowledge Base - Memory system for incident history.
 *
 * Like OpenClaw's hybrid BM25 + vector search memory, this provides
 * a searchable knowledge base of past incidents for faster resolution.
 *
 * Features:
 * - Search incidents by keywords, service, severity
 * - Find similar past incidents
 * - Track resolution patterns
 * - Provide context to the agent for faster triage
 */
@Slf4j
@Service
public class IncidentKnowledgeBase {

    private final IncidentRepository incidentRepository;
    private final LearningService learningService;

    public IncidentKnowledgeBase(IncidentRepository incidentRepository,
                                  @Lazy LearningService learningService) {
        this.incidentRepository = incidentRepository;
        this.learningService = learningService;
    }

    /**
     * Search for similar past incidents based on keywords.
     */
    public List<Incident> searchSimilarIncidents(String query, String service) {
        List<Incident> allIncidents = service != null
                ? incidentRepository.findByService(service)
                : incidentRepository.findAll();

        if (query == null || query.isEmpty()) {
            return allIncidents.stream()
                    .sorted(Comparator.comparing(Incident::getCreatedAt).reversed())
                    .limit(10)
                    .collect(Collectors.toList());
        }

        // Simple keyword matching (in production, use vector search)
        String[] keywords = query.toLowerCase().split("\\s+");

        return allIncidents.stream()
                .filter(incident -> {
                    String searchText = String.join(" ",
                            incident.getTitle() != null ? incident.getTitle() : "",
                            incident.getDescription() != null ? incident.getDescription() : "",
                            incident.getRootCause() != null ? incident.getRootCause() : "",
                            incident.getResolution() != null ? incident.getResolution() : ""
                    ).toLowerCase();

                    return Arrays.stream(keywords).anyMatch(searchText::contains);
                })
                .sorted(Comparator.comparing(Incident::getCreatedAt).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }

    /**
     * Get incident statistics for a service.
     */
    public Map<String, Object> getServiceIncidentStats(String service) {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        long recentCount = incidentRepository.countIncidentsByServiceSince(service, thirtyDaysAgo);

        List<Incident> allServiceIncidents = incidentRepository.findByService(service);
        Map<Incident.Severity, Long> severityDistribution = allServiceIncidents.stream()
                .collect(Collectors.groupingBy(Incident::getSeverity, Collectors.counting()));

        long avgResolutionTime = (long) allServiceIncidents.stream()
                .filter(i -> i.getResolvedAt() != null && i.getCreatedAt() != null)
                .mapToLong(i -> i.getResolvedAt().toEpochMilli() - i.getCreatedAt().toEpochMilli())
                .average()
                .orElse(0);

        return Map.of(
                "service", service,
                "total_incidents", allServiceIncidents.size(),
                "recent_30d", recentCount,
                "severity_distribution", severityDistribution,
                "avg_resolution_time_ms", avgResolutionTime,
                "most_common_root_causes", getCommonRootCauses(allServiceIncidents)
        );
    }

    /**
     * Get the most common root causes for a set of incidents.
     */
    private List<String> getCommonRootCauses(List<Incident> incidents) {
        return incidents.stream()
                .filter(i -> i.getRootCause() != null && !i.getRootCause().isEmpty())
                .collect(Collectors.groupingBy(Incident::getRootCause, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Build context string for the agent about a service's incident history.
     */
    public String buildContextForService(String service) {
        Map<String, Object> stats = getServiceIncidentStats(service);
        List<Incident> recentIncidents = searchSimilarIncidents(null, service);

        StringBuilder context = new StringBuilder();
        context.append(String.format("## Incident History for %s\n", service));
        context.append(String.format("Total incidents: %s\n", stats.get("total_incidents")));
        context.append(String.format("Recent (30d): %s\n", stats.get("recent_30d")));

        if (!recentIncidents.isEmpty()) {
            context.append("\nRecent incidents:\n");
            for (Incident incident : recentIncidents.subList(0, Math.min(5, recentIncidents.size()))) {
                context.append(String.format("- [%s] %s - %s (root cause: %s)\n",
                        incident.getSeverity(), incident.getTitle(), incident.getStatus(),
                        incident.getRootCause() != null ? incident.getRootCause() : "unknown"));
            }
        }

        @SuppressWarnings("unchecked")
        List<String> rootCauses = (List<String>) stats.get("most_common_root_causes");
        if (rootCauses != null && !rootCauses.isEmpty()) {
            context.append("\nCommon root causes:\n");
            rootCauses.forEach(rc -> context.append("- ").append(rc).append("\n"));
        }

        // Add learning data if available
        try {
            String recommendation = learningService.getRecommendedApproach(service);
            if (recommendation != null) {
                context.append("\n").append(recommendation).append("\n");
            }
            double successRate = learningService.getSuccessRate(service);
            if (successRate > 0) {
                context.append(String.format("Auto-resolution success rate: %.1f%%\n", successRate));
            }
        } catch (Exception e) {
            // Learning service may not have data yet
        }

        return context.toString();
    }
}
