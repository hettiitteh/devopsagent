package com.example.devopsagent.controller;

import com.example.devopsagent.service.LearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Learning & Insights REST API Controller.
 */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;

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
}
