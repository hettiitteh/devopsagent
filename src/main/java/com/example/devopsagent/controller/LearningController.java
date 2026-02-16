package com.example.devopsagent.controller;

import com.example.devopsagent.domain.ResolutionRecord;
import com.example.devopsagent.repository.ResolutionRecordRepository;
import com.example.devopsagent.service.LearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Learning & Insights REST API Controller.
 */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;
    private final ResolutionRecordRepository resolutionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Get overall resolution insights for the dashboard.
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getInsights() {
        return ResponseEntity.ok(learningService.getInsights());
    }

    /**
     * Get recommended approach for a service.
     */
    @GetMapping("/recommend/{service}")
    public ResponseEntity<Map<String, Object>> getRecommendation(@PathVariable String service) {
        String recommendation = learningService.getRecommendedApproach(service);
        double successRate = learningService.getSuccessRate(service);
        return ResponseEntity.ok(Map.of(
                "service", service,
                "recommendation", recommendation != null ? recommendation : "No resolution data available yet.",
                "success_rate", successRate
        ));
    }

    // ─── Executive Summary ───

    @GetMapping("/executive-summary")
    public ResponseEntity<Map<String, Object>> getExecutiveSummary() {
        return ResponseEntity.ok(learningService.getExecutiveSummary());
    }

    @PostMapping("/executive-summary/refresh")
    public ResponseEntity<Map<String, Object>> refreshExecutiveSummary() {
        return ResponseEntity.ok(learningService.refreshExecutiveSummary());
    }

    // ─── CRUD for Resolution Records ───

    /**
     * List all resolution records, optionally filtered by service.
     */
    @GetMapping("/records")
    public ResponseEntity<List<ResolutionRecord>> listRecords(
            @RequestParam(required = false) String service) {
        List<ResolutionRecord> records;
        if (service != null && !service.isBlank()) {
            records = resolutionRepository.findByServiceOrderByCreatedAtDesc(service);
        } else {
            records = resolutionRepository.findAll().stream()
                    .sorted(Comparator.comparing(ResolutionRecord::getCreatedAt).reversed())
                    .toList();
        }
        return ResponseEntity.ok(records);
    }

    /**
     * Get a single resolution record by ID.
     */
    @GetMapping("/records/{id}")
    public ResponseEntity<ResolutionRecord> getRecord(@PathVariable String id) {
        return resolutionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new manual learning record.
     */
    @PostMapping("/records")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ResolutionRecord> createRecord(@RequestBody Map<String, Object> body) {
        try {
            String toolSequenceJson;
            Object toolSeqObj = body.get("toolSequence");
            if (toolSeqObj instanceof List) {
                toolSequenceJson = objectMapper.writeValueAsString(toolSeqObj);
            } else if (toolSeqObj instanceof String str) {
                // Accept comma-separated string and convert to JSON array
                List<String> tools = List.of(str.split("\\s*,\\s*"));
                toolSequenceJson = objectMapper.writeValueAsString(tools);
            } else {
                toolSequenceJson = "[]";
            }

            Number resTimeNum = (Number) body.getOrDefault("resolutionTimeMs", 0);

            ResolutionRecord record = ResolutionRecord.builder()
                    .service((String) body.get("service"))
                    .incidentId((String) body.get("incidentId"))
                    .incidentTitle((String) body.get("incidentTitle"))
                    .toolSequence(toolSequenceJson)
                    .success(Boolean.TRUE.equals(body.get("success")))
                    .resolutionTimeMs(resTimeNum.longValue())
                    .createdAt(Instant.now())
                    .build();

            ResolutionRecord saved = resolutionRepository.save(record);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update an existing resolution record.
     */
    @PutMapping("/records/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ResolutionRecord> updateRecord(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return resolutionRepository.findById(id).map(record -> {
            try {
                if (body.containsKey("service")) record.setService((String) body.get("service"));
                if (body.containsKey("incidentId")) record.setIncidentId((String) body.get("incidentId"));
                if (body.containsKey("incidentTitle")) record.setIncidentTitle((String) body.get("incidentTitle"));
                if (body.containsKey("success")) record.setSuccess(Boolean.TRUE.equals(body.get("success")));
                if (body.containsKey("resolutionTimeMs")) {
                    record.setResolutionTimeMs(((Number) body.get("resolutionTimeMs")).longValue());
                }
                if (body.containsKey("toolSequence")) {
                    Object toolSeqObj = body.get("toolSequence");
                    if (toolSeqObj instanceof List) {
                        record.setToolSequence(objectMapper.writeValueAsString(toolSeqObj));
                    } else if (toolSeqObj instanceof String str) {
                        List<String> tools = List.of(str.split("\\s*,\\s*"));
                        record.setToolSequence(objectMapper.writeValueAsString(tools));
                    }
                }
                return ResponseEntity.ok(resolutionRepository.save(record));
            } catch (Exception e) {
                return ResponseEntity.badRequest().<ResolutionRecord>build();
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a resolution record.
     */
    @DeleteMapping("/records/{id}")
    public ResponseEntity<Map<String, String>> deleteRecord(@PathVariable String id) {
        if (resolutionRepository.existsById(id)) {
            resolutionRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
        }
        return ResponseEntity.notFound().build();
    }
}
