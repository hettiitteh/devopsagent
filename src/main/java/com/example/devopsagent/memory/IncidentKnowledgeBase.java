package com.example.devopsagent.memory;

import com.example.devopsagent.agent.AgentMessage;
import com.example.devopsagent.agent.LlmClient;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.embedding.EmbeddingService;
import com.example.devopsagent.embedding.VectorStore;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.service.LearningService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public IncidentKnowledgeBase(IncidentRepository incidentRepository,
                                  @Lazy LearningService learningService,
                                  @Lazy LlmClient llmClient,
                                  ObjectMapper objectMapper,
                                  @Lazy EmbeddingService embeddingService,
                                  @Lazy VectorStore vectorStore) {
        this.incidentRepository = incidentRepository;
        this.learningService = learningService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * Search for similar past incidents.  Uses embedding-based vector similarity
     * when available (fast, single API call to embed the query), falling back to
     * the legacy keyword + LLM expansion + LLM ranking pipeline when embeddings
     * are disabled or return no results.
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

        // ── Fast path: embedding-based search ──
        if (embeddingService.isEnabled()) {
            try {
                float[] queryVec = embeddingService.embed(query);
                if (queryVec != null) {
                    List<VectorStore.ScoredResult> results = vectorStore.findSimilar(queryVec, "incident", 10, 0.65);
                    if (!results.isEmpty()) {
                        // Resolve entity IDs to Incident objects, preserving similarity order
                        Set<String> idSet = results.stream()
                                .map(VectorStore.ScoredResult::entityId)
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                        Map<String, Incident> incidentMap = allIncidents.stream()
                                .filter(i -> idSet.contains(i.getId()))
                                .collect(Collectors.toMap(Incident::getId, i -> i, (a, b) -> a));

                        List<Incident> ranked = results.stream()
                                .map(r -> incidentMap.get(r.entityId()))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());

                        if (!ranked.isEmpty()) {
                            log.debug("Embedding search for '{}' returned {} results (skipped LLM expansion + ranking)",
                                    query, ranked.size());
                            return ranked;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Embedding search failed for '{}', falling back to keyword search: {}", query, e.getMessage());
            }
        }

        // ── Fallback: keyword + LLM pipeline ──
        return searchSimilarIncidentsKeyword(query, allIncidents);
    }

    /**
     * Legacy keyword-based search with LLM query expansion and ranking.
     */
    private List<Incident> searchSimilarIncidentsKeyword(String query, List<Incident> allIncidents) {
        // Step 1: Expand query with LLM for better recall
        List<String> expandedTerms = expandSearchQuery(query);

        // Step 2: Filter using expanded keywords (broader recall)
        List<Incident> filtered = allIncidents.stream()
                .filter(incident -> {
                    String searchText = String.join(" ",
                            incident.getTitle() != null ? incident.getTitle() : "",
                            incident.getDescription() != null ? incident.getDescription() : "",
                            incident.getRootCause() != null ? incident.getRootCause() : "",
                            incident.getResolution() != null ? incident.getResolution() : ""
                    ).toLowerCase();

                    return expandedTerms.stream().anyMatch(searchText::contains);
                })
                .sorted(Comparator.comparing(Incident::getCreatedAt).reversed())
                .limit(20)
                .collect(Collectors.toList());

        // Step 3: Rank by relevance using LLM (better precision)
        return rankByRelevance(query, filtered);
    }

    /**
     * Use LLM to expand a search query with synonyms, related technical terms,
     * and common variations for better recall.
     */
    private List<String> expandSearchQuery(String query) {
        try {
            String prompt = String.format("""
                    Expand this incident search query with synonyms, related technical terms, \
                    and common variations. Include the original terms plus expansions.
                    
                    Query: %s
                    
                    Respond ONLY with a JSON array of 10-15 lowercase search terms. Example:
                    ["database", "db", "connection refused", "mysql", "pool exhausted", "timeout"]
                    """, query);

            List<AgentMessage> messages = List.of(
                    AgentMessage.system(prompt),
                    AgentMessage.user("Expand this query now.")
            );
            AgentMessage response = llmClient.chat(messages, List.of());
            String content = response.getContent();

            if (content != null && !content.isBlank()) {
                content = content.trim();
                if (content.startsWith("```")) {
                    content = content.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                }
                List<String> terms = objectMapper.readValue(content, new TypeReference<List<String>>() {});
                log.debug("LLM expanded query '{}' to {} terms: {}", query, terms.size(), terms);
                return terms.stream().map(String::toLowerCase).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("LLM query expansion failed for '{}', falling back to keyword split: {}", query, e.getMessage());
        }
        // Fallback: original keyword split
        return Arrays.asList(query.toLowerCase().split("\\s+"));
    }

    /**
     * Use LLM to rank incident search results by relevance to the query.
     * Only invoked when there are more than 5 candidates, to save LLM calls.
     */
    private List<Incident> rankByRelevance(String query, List<Incident> candidates) {
        if (candidates.size() <= 5) {
            return candidates;
        }

        try {
            StringBuilder candidateList = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                Incident inc = candidates.get(i);
                candidateList.append(String.format("%d. [%s] %s — %s (root cause: %s)\n",
                        i + 1, inc.getId(), inc.getTitle(),
                        inc.getDescription() != null ? inc.getDescription().substring(0, Math.min(100, inc.getDescription().length())) : "N/A",
                        inc.getRootCause() != null ? inc.getRootCause() : "unknown"));
            }

            String prompt = String.format("""
                    Rank these incidents by relevance to the query: "%s"
                    
                    Candidates:
                    %s
                    
                    Return the top 10 most relevant incidents as a JSON array of their 1-based index numbers, \
                    ordered from most to least relevant.
                    Example: [3, 1, 7, 2, 5]
                    Respond ONLY with the JSON array.
                    """, query, candidateList);

            List<AgentMessage> messages = List.of(
                    AgentMessage.system(prompt),
                    AgentMessage.user("Rank these incidents now.")
            );
            AgentMessage response = llmClient.chat(messages, List.of());
            String content = response.getContent();

            if (content != null && !content.isBlank()) {
                content = content.trim();
                if (content.startsWith("```")) {
                    content = content.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                }
                List<Integer> rankedIndices = objectMapper.readValue(content, new TypeReference<List<Integer>>() {});
                List<Incident> ranked = new ArrayList<>();
                Set<Integer> seen = new HashSet<>();
                for (int idx : rankedIndices) {
                    int zeroIdx = idx - 1;
                    if (zeroIdx >= 0 && zeroIdx < candidates.size() && seen.add(zeroIdx)) {
                        ranked.add(candidates.get(zeroIdx));
                    }
                    if (ranked.size() >= 10) break;
                }
                log.debug("LLM ranked {} candidates for query '{}' → top {}", candidates.size(), query, ranked.size());
                return ranked;
            }
        } catch (Exception e) {
            log.warn("LLM relevance ranking failed for '{}', using chronological order: {}", query, e.getMessage());
        }
        // Fallback: return top 10 by recency
        return candidates.stream().limit(10).collect(Collectors.toList());
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
