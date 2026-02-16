package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persists embedding vectors for incidents, resolutions, and RCA reports.
 * The embedding column stores a comma-separated float array as TEXT
 * (e.g. "0.0123,-0.0456,0.0789,..."). Cosine similarity is computed in Java.
 */
@Entity
@Table(name = "embedding_records", indexes = {
        @Index(name = "idx_emb_entity_type", columnList = "entity_type"),
        @Index(name = "idx_emb_entity_type_id", columnList = "entity_type, entity_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Type of entity: "incident", "resolution", "rca" */
    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    /** ID of the source entity (incident ID, resolution record ID, or RCA report ID) */
    @Column(name = "entity_id", nullable = false)
    private String entityId;

    /** SHA-256 hash of the source text used for embedding — allows change detection */
    @Column(name = "text_hash", length = 64)
    private String textHash;

    /** Comma-separated float values representing the embedding vector */
    @Column(name = "embedding", columnDefinition = "TEXT", nullable = false)
    private String embedding;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
