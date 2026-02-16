package com.example.devopsagent.controller;

import com.example.devopsagent.embedding.EmbeddingMigrationService;
import com.example.devopsagent.embedding.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for embedding status, migration, and reindexing.
 */
@RestController
@RequestMapping("/api/embeddings")
@RequiredArgsConstructor
public class EmbeddingController {

    private final EmbeddingMigrationService migrationService;
    private final VectorStore vectorStore;

    /**
     * Get embedding subsystem status: counts by entity type, model, migration state.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new java.util.HashMap<>(migrationService.getStatus());
        status.put("inMemoryCount", vectorStore.size());
        status.put("inMemoryByType", vectorStore.countByEntityType());
        return ResponseEntity.ok(status);
    }

    /**
     * Trigger a full re-embed of ALL records (forces fresh embedding even if already present).
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        int count = migrationService.reindexAll();
        return ResponseEntity.ok(Map.of(
                "message", "Reindex complete",
                "embeddingsGenerated", count
        ));
    }

    /**
     * Trigger migration of records that are missing embeddings (no-op for already embedded records).
     */
    @PostMapping("/migrate")
    public ResponseEntity<Map<String, Object>> migrate() {
        int count = migrationService.migrateAll();
        return ResponseEntity.ok(Map.of(
                "message", "Migration complete",
                "newEmbeddings", count
        ));
    }
}
