package com.example.devopsagent.embedding;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.RcaReport;
import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.repository.EmbeddingRecordRepository;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.RcaReportRepository;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * On application startup, checks for existing incidents, resolutions, and RCAs
 * that don't yet have embeddings, and generates them asynchronously.
 * Also provides a reindex method that can be triggered from the UI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingMigrationService {

    private final AgentProperties properties;
    private final EmbeddingService embeddingService;
    private final EmbeddingRecordRepository embeddingRepository;
    private final IncidentRepository incidentRepository;
    private final ResolutionRecordRepository resolutionRepository;
    private final RcaReportRepository rcaRepository;

    private volatile boolean migrationRunning = false;
    private volatile Instant lastMigrationAt;
    private volatile int lastMigrationCount;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onStartup() {
        if (!properties.getLlm().getEmbedding().isEnabled()) {
            log.info("Embeddings disabled — skipping migration");
            return;
        }

        // Small delay to let the application fully start
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        log.info("Starting embedding migration for existing records...");
        int count = migrateAll();
        log.info("Embedding migration complete — {} new embeddings generated", count);
    }

    /**
     * Embed all records that are missing embeddings.
     * Called on startup and when the user presses "Re-index" in the UI.
     */
    public int migrateAll() {
        if (migrationRunning) {
            log.warn("Embedding migration already running — skipping");
            return 0;
        }
        migrationRunning = true;
        int total = 0;

        try {
            total += migrateIncidents();
            total += migrateResolutions();
            total += migrateRcas();
        } catch (Exception e) {
            log.error("Embedding migration failed: {}", e.getMessage());
        } finally {
            migrationRunning = false;
            lastMigrationAt = Instant.now();
            lastMigrationCount = total;
        }

        return total;
    }

    /**
     * Force re-embed ALL records (even those already embedded).
     */
    public int reindexAll() {
        if (migrationRunning) {
            log.warn("Embedding migration already running — skipping reindex");
            return 0;
        }
        migrationRunning = true;
        int total = 0;

        try {
            // Incidents
            List<Incident> incidents = incidentRepository.findAll();
            for (Incident incident : incidents) {
                try {
                    String text = embeddingService.buildIncidentText(incident);
                    embeddingService.upsertEmbedding("incident", incident.getId(), text);
                    total++;
                } catch (Exception e) {
                    log.warn("Failed to reindex incident {}: {}", incident.getId(), e.getMessage());
                }
            }

            // Resolutions
            List<ResolutionRecord> resolutions = resolutionRepository.findAll();
            for (ResolutionRecord record : resolutions) {
                try {
                    String text = embeddingService.buildResolutionText(record);
                    embeddingService.upsertEmbedding("resolution", record.getId(), text);
                    total++;
                } catch (Exception e) {
                    log.warn("Failed to reindex resolution {}: {}", record.getId(), e.getMessage());
                }
            }

            // RCAs
            List<RcaReport> rcas = rcaRepository.findAll();
            for (RcaReport rca : rcas) {
                try {
                    String text = embeddingService.buildRcaText(rca);
                    embeddingService.upsertEmbedding("rca", rca.getId(), text);
                    total++;
                } catch (Exception e) {
                    log.warn("Failed to reindex RCA {}: {}", rca.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Reindex failed: {}", e.getMessage());
        } finally {
            migrationRunning = false;
            lastMigrationAt = Instant.now();
            lastMigrationCount = total;
        }

        return total;
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "enabled", properties.getLlm().getEmbedding().isEnabled(),
                "migrationRunning", migrationRunning,
                "lastMigrationAt", lastMigrationAt != null ? lastMigrationAt.toString() : "never",
                "lastMigrationCount", lastMigrationCount,
                "totalEmbeddings", embeddingRepository.count(),
                "incidentEmbeddings", embeddingRepository.countByEntityType("incident"),
                "resolutionEmbeddings", embeddingRepository.countByEntityType("resolution"),
                "rcaEmbeddings", embeddingRepository.countByEntityType("rca"),
                "model", properties.getLlm().getEmbedding().getModel(),
                "dimensions", properties.getLlm().getEmbedding().getDimensions()
        );
    }

    // ── Private migration methods ──

    private int migrateIncidents() {
        Set<String> existingIds = embeddingRepository.findAllByEntityType("incident").stream()
                .map(r -> r.getEntityId())
                .collect(Collectors.toSet());

        List<Incident> incidents = incidentRepository.findAll();
        int count = 0;

        for (Incident incident : incidents) {
            if (existingIds.contains(incident.getId())) continue;
            try {
                String text = embeddingService.buildIncidentText(incident);
                if (text != null && !text.isBlank()) {
                    embeddingService.upsertEmbedding("incident", incident.getId(), text);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Migration: failed to embed incident {}: {}", incident.getId(), e.getMessage());
            }
        }

        if (count > 0) {
            log.info("Migrated {} incident embeddings (of {} total incidents)", count, incidents.size());
        }
        return count;
    }

    private int migrateResolutions() {
        Set<String> existingIds = embeddingRepository.findAllByEntityType("resolution").stream()
                .map(r -> r.getEntityId())
                .collect(Collectors.toSet());

        List<ResolutionRecord> records = resolutionRepository.findAll();
        int count = 0;

        for (ResolutionRecord record : records) {
            if (existingIds.contains(record.getId())) continue;
            try {
                String text = embeddingService.buildResolutionText(record);
                if (text != null && !text.isBlank()) {
                    embeddingService.upsertEmbedding("resolution", record.getId(), text);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Migration: failed to embed resolution {}: {}", record.getId(), e.getMessage());
            }
        }

        if (count > 0) {
            log.info("Migrated {} resolution embeddings (of {} total records)", count, records.size());
        }
        return count;
    }

    private int migrateRcas() {
        Set<String> existingIds = embeddingRepository.findAllByEntityType("rca").stream()
                .map(r -> r.getEntityId())
                .collect(Collectors.toSet());

        List<RcaReport> rcas = rcaRepository.findAll();
        int count = 0;

        for (RcaReport rca : rcas) {
            if (existingIds.contains(rca.getId())) continue;
            try {
                String text = embeddingService.buildRcaText(rca);
                if (text != null && !text.isBlank()) {
                    embeddingService.upsertEmbedding("rca", rca.getId(), text);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Migration: failed to embed RCA {}: {}", rca.getId(), e.getMessage());
            }
        }

        if (count > 0) {
            log.info("Migrated {} RCA embeddings (of {} total RCAs)", count, rcas.size());
        }
        return count;
    }
}
