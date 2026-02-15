package com.example.devopsagent.controller;

import com.example.devopsagent.domain.RiskAssessment;
import com.example.devopsagent.repository.RiskAssessmentRepository;
import com.example.devopsagent.service.ProactiveAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST API for Proactive Risk Assessments.
 */
@RestController
@RequestMapping("/api/risks")
@RequiredArgsConstructor
public class RiskController {

    private final RiskAssessmentRepository riskRepository;
    private final ProactiveAnalysisService proactiveAnalysisService;

    /**
     * List all risks, optionally filtered by status.
     */
    @GetMapping
    public List<RiskAssessment> listRisks(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            try {
                RiskAssessment.Status s = RiskAssessment.Status.valueOf(status.toUpperCase());
                return riskRepository.findByStatusOrderByCreatedAtDesc(s);
            } catch (IllegalArgumentException e) {
                // fall through to all
            }
        }
        return riskRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Get a single risk by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<RiskAssessment> getRisk(@PathVariable String id) {
        return riskRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the latest analysis results (most recent risks).
     */
    @GetMapping("/latest")
    public List<RiskAssessment> getLatestRisks() {
        return riskRepository.findAllByOrderByCreatedAtDesc().stream()
                .limit(10)
                .toList();
    }

    /**
     * Acknowledge a risk.
     */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<RiskAssessment> acknowledgeRisk(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        return riskRepository.findById(id)
                .map(risk -> {
                    risk.setStatus(RiskAssessment.Status.ACKNOWLEDGED);
                    risk.setAcknowledgedAt(Instant.now());
                    risk.setAcknowledgedBy(body != null ? body.getOrDefault("user", "operator") : "operator");
                    riskRepository.save(risk);
                    return ResponseEntity.ok(risk);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Mark a risk as mitigated.
     */
    @PostMapping("/{id}/mitigate")
    public ResponseEntity<RiskAssessment> mitigateRisk(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        return riskRepository.findById(id)
                .map(risk -> {
                    risk.setStatus(RiskAssessment.Status.MITIGATED);
                    if (risk.getAcknowledgedAt() == null) {
                        risk.setAcknowledgedAt(Instant.now());
                        risk.setAcknowledgedBy(body != null ? body.getOrDefault("user", "operator") : "operator");
                    }
                    riskRepository.save(risk);
                    return ResponseEntity.ok(risk);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Manually trigger a proactive analysis.
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> triggerAnalysis() {
        List<RiskAssessment> newRisks = proactiveAnalysisService.analyzeSystemRisks();
        return ResponseEntity.ok(Map.of(
                "message", "Proactive analysis completed",
                "new_risks", newRisks.size(),
                "risks", newRisks
        ));
    }

    /**
     * Get counts by status for the dashboard.
     */
    @GetMapping("/counts")
    public Map<String, Long> getCounts() {
        return Map.of(
                "open", riskRepository.countByStatus(RiskAssessment.Status.OPEN),
                "acknowledged", riskRepository.countByStatus(RiskAssessment.Status.ACKNOWLEDGED),
                "mitigated", riskRepository.countByStatus(RiskAssessment.Status.MITIGATED)
        );
    }
}
