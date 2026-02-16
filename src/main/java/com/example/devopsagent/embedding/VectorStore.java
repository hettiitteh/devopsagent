package com.example.devopsagent.embedding;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.EmbeddingRecord;
import com.example.devopsagent.repository.EmbeddingRecordRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector index backed by the embedding_records table.
 * <p>
 * On startup, loads all persisted embeddings into a {@link ConcurrentHashMap}
 * keyed by {@code entityType:entityId}.  Provides a fast {@link #findSimilar}
 * method that computes cosine similarity in pure Java — plenty fast for the
 * low-thousands-of-records scale of this agent.
 */
@Slf4j
@Component
public class VectorStore {

    private final EmbeddingRecordRepository embeddingRepository;
    private final AgentProperties properties;

    /** key = "incident:abc-123", value = float[] vector */
    private final ConcurrentHashMap<String, float[]> index = new ConcurrentHashMap<>();

    public VectorStore(EmbeddingRecordRepository embeddingRepository,
                       AgentProperties properties) {
        this.embeddingRepository = embeddingRepository;
        this.properties = properties;
    }

    @PostConstruct
    public void loadFromDatabase() {
        if (!properties.getLlm().getEmbedding().isEnabled()) {
            log.info("Embeddings disabled — VectorStore not loading");
            return;
        }

        List<EmbeddingRecord> all = embeddingRepository.findAll();
        int loaded = 0;
        for (EmbeddingRecord rec : all) {
            try {
                float[] vec = EmbeddingService.parseEmbedding(rec.getEmbedding());
                if (vec != null) {
                    index.put(key(rec.getEntityType(), rec.getEntityId()), vec);
                    loaded++;
                }
            } catch (Exception e) {
                log.warn("Failed to parse embedding for {}:{} — skipping", rec.getEntityType(), rec.getEntityId());
            }
        }
        log.info("VectorStore loaded {} embeddings from DB (of {} records)", loaded, all.size());
    }

    // ── Mutation ──

    public void put(String entityType, String entityId, float[] vector) {
        index.put(key(entityType, entityId), vector);
    }

    public void remove(String entityType, String entityId) {
        index.remove(key(entityType, entityId));
    }

    // ── Query ──

    /**
     * Find the top-K most similar entries to the query vector, filtered by
     * entity type and above the similarity threshold.
     *
     * @param query      the query vector (e.g. an embedded search string)
     * @param entityType filter to this entity type ("incident", "resolution", "rca"), or null for all
     * @param topK       max results to return
     * @param threshold  minimum cosine similarity (0.0 – 1.0)
     * @return list of {@link ScoredResult} sorted by descending similarity
     */
    public List<ScoredResult> findSimilar(float[] query, String entityType, int topK, double threshold) {
        if (query == null || index.isEmpty()) return List.of();

        String prefix = entityType != null ? entityType + ":" : null;

        return index.entrySet().parallelStream()
                .filter(e -> prefix == null || e.getKey().startsWith(prefix))
                .map(e -> {
                    double sim = EmbeddingService.cosineSimilarity(query, e.getValue());
                    String[] parts = e.getKey().split(":", 2);
                    return new ScoredResult(parts[0], parts.length > 1 ? parts[1] : e.getKey(), sim);
                })
                .filter(r -> r.score() >= threshold)
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * Get total number of embeddings in the in-memory index.
     */
    public int size() {
        return index.size();
    }

    /**
     * Get count of embeddings by entity type.
     */
    public Map<String, Long> countByEntityType() {
        return index.keySet().stream()
                .map(k -> k.split(":", 2)[0])
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
    }

    /**
     * Clear all in-memory entries (does NOT clear the database).
     */
    public void clearMemory() {
        index.clear();
    }

    // ── Helpers ──

    private static String key(String entityType, String entityId) {
        return entityType + ":" + entityId;
    }

    /**
     * A search result with entity type, entity ID, and cosine similarity score.
     */
    public record ScoredResult(String entityType, String entityId, double score) {}
}
