package com.example.devopsagent.embedding;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.EmbeddingRecord;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.RcaReport;
import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.repository.EmbeddingRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Calls the OpenAI /v1/embeddings endpoint (or compatible) to generate
 * vector representations of text.  Results are cached in Caffeine and
 * persisted to the embedding_records table via {@link EmbeddingRecordRepository}.
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final String OPENAI_EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json");

    private final AgentProperties properties;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingRecordRepository embeddingRepository;
    private final VectorStore vectorStore;

    /** In-process cache keyed by SHA-256 of input text → float[] vector */
    private final Cache<String, float[]> embeddingCache;

    public EmbeddingService(AgentProperties properties,
                            OkHttpClient httpClient,
                            ObjectMapper objectMapper,
                            EmbeddingRecordRepository embeddingRepository,
                            VectorStore vectorStore) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.embeddingRepository = embeddingRepository;
        this.vectorStore = vectorStore;

        this.embeddingCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    // ── Public API ──

    /**
     * Check whether the embedding subsystem is configured and enabled.
     */
    public boolean isEnabled() {
        AgentProperties.LlmConfig.EmbeddingConfig cfg = properties.getLlm().getEmbedding();
        return cfg.isEnabled()
                && properties.getLlm().getApiKey() != null
                && !properties.getLlm().getApiKey().isBlank();
    }

    /**
     * Embed a single piece of text.  Returns a float[] vector of the
     * configured dimension, or null if the subsystem is disabled or the
     * API call fails.
     */
    public float[] embed(String text) {
        if (!isEnabled() || text == null || text.isBlank()) return null;

        String hash = sha256(text);
        float[] cached = embeddingCache.getIfPresent(hash);
        if (cached != null) return cached;

        try {
            float[] vector = callEmbeddingApi(text);
            if (vector != null) {
                embeddingCache.put(hash, vector);
            }
            return vector;
        } catch (Exception e) {
            log.warn("Embedding API call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Cosine similarity between two vectors.  Returns a value in [-1, 1].
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    // ── Async convenience methods for each entity type ──

    @Async
    public void embedIncidentAsync(Incident incident) {
        if (!isEnabled() || incident == null) return;
        try {
            String text = buildIncidentText(incident);
            upsertEmbedding("incident", incident.getId(), text);
        } catch (Exception e) {
            log.warn("Failed to embed incident {}: {}", incident.getId(), e.getMessage());
        }
    }

    @Async
    public void embedResolutionAsync(ResolutionRecord record) {
        if (!isEnabled() || record == null) return;
        try {
            String text = buildResolutionText(record);
            upsertEmbedding("resolution", record.getId(), text);
        } catch (Exception e) {
            log.warn("Failed to embed resolution {}: {}", record.getId(), e.getMessage());
        }
    }

    @Async
    public void embedRcaAsync(RcaReport rca) {
        if (!isEnabled() || rca == null) return;
        try {
            String text = buildRcaText(rca);
            upsertEmbedding("rca", rca.getId(), text);
        } catch (Exception e) {
            log.warn("Failed to embed RCA {}: {}", rca.getId(), e.getMessage());
        }
    }

    // ── Text builders ──

    public String buildIncidentText(Incident incident) {
        StringBuilder sb = new StringBuilder();
        if (incident.getTitle() != null) sb.append(incident.getTitle()).append(". ");
        if (incident.getDescription() != null) sb.append(incident.getDescription()).append(" ");
        if (incident.getRootCause() != null) sb.append("Root cause: ").append(incident.getRootCause()).append(". ");
        if (incident.getResolution() != null) sb.append("Resolution: ").append(incident.getResolution());
        return sb.toString().trim();
    }

    public String buildResolutionText(ResolutionRecord record) {
        StringBuilder sb = new StringBuilder();
        if (record.getIncidentTitle() != null) sb.append(record.getIncidentTitle()).append(". ");
        if (record.getService() != null) sb.append("Service: ").append(record.getService()).append(". ");
        if (record.getToolSequence() != null) sb.append("Tools: ").append(record.getToolSequence());
        return sb.toString().trim();
    }

    public String buildRcaText(RcaReport rca) {
        StringBuilder sb = new StringBuilder();
        if (rca.getSummary() != null) sb.append(rca.getSummary()).append(" ");
        if (rca.getRootCause() != null) sb.append("Root cause: ").append(rca.getRootCause()).append(". ");
        if (rca.getLessonsLearned() != null) sb.append("Lessons: ").append(rca.getLessonsLearned());
        return sb.toString().trim();
    }

    // ── Internal ──

    /**
     * Embed text and persist/update in DB + VectorStore.
     */
    public void upsertEmbedding(String entityType, String entityId, String text) {
        if (text == null || text.isBlank()) return;

        String hash = sha256(text);

        // Check if unchanged
        Optional<EmbeddingRecord> existing = embeddingRepository.findByEntityTypeAndEntityId(entityType, entityId);
        if (existing.isPresent() && hash.equals(existing.get().getTextHash())) {
            // Already up-to-date, just ensure it's in the in-memory store
            float[] vec = parseEmbedding(existing.get().getEmbedding());
            if (vec != null) {
                vectorStore.put(entityType, entityId, vec);
            }
            return;
        }

        float[] vector = embed(text);
        if (vector == null) return;

        String csvEmbedding = serializeEmbedding(vector);

        if (existing.isPresent()) {
            EmbeddingRecord record = existing.get();
            record.setTextHash(hash);
            record.setEmbedding(csvEmbedding);
            record.setCreatedAt(Instant.now());
            embeddingRepository.save(record);
        } else {
            EmbeddingRecord record = EmbeddingRecord.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .textHash(hash)
                    .embedding(csvEmbedding)
                    .createdAt(Instant.now())
                    .build();
            embeddingRepository.save(record);
        }

        vectorStore.put(entityType, entityId, vector);
        log.debug("Upserted embedding for {}:{}", entityType, entityId);
    }

    // ── Serialization helpers ──

    public static String serializeEmbedding(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    public static float[] parseEmbedding(String csv) {
        if (csv == null || csv.isBlank()) return null;
        String[] parts = csv.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    // ── OpenAI API call ──

    private float[] callEmbeddingApi(String text) throws Exception {
        AgentProperties.LlmConfig.EmbeddingConfig cfg = properties.getLlm().getEmbedding();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("input", text);
        if (cfg.getDimensions() > 0) {
            body.put("dimensions", cfg.getDimensions());
        }

        Request request = new Request.Builder()
                .url(OPENAI_EMBEDDINGS_URL)
                .addHeader("Authorization", "Bearer " + properties.getLlm().getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA))
                .build();

        OkHttpClient client = httpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "unknown";
                log.error("Embedding API error {}: {}", response.code(), err);
                return null;
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            ArrayNode dataArray = (ArrayNode) root.get("data");
            if (dataArray == null || dataArray.isEmpty()) return null;

            ArrayNode embeddingArray = (ArrayNode) dataArray.get(0).get("embedding");
            if (embeddingArray == null) return null;

            float[] vector = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                vector[i] = (float) embeddingArray.get(i).asDouble();
            }
            return vector;
        }
    }
}
