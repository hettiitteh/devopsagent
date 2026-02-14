package com.example.devopsagent.controller;

import com.example.devopsagent.domain.MonitoredService;
import com.example.devopsagent.monitoring.AnomalyDetector;
import com.example.devopsagent.monitoring.MonitoringService;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Monitoring REST API Controller.
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;
    private final MonitoredServiceRepository serviceRepository;
    private final AnomalyDetector anomalyDetector;

    /**
     * Get health summary of all services.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        return ResponseEntity.ok(monitoringService.getHealthSummary());
    }

    /**
     * Register a new service for monitoring.
     */
    @PostMapping("/services")
    public ResponseEntity<MonitoredService> registerService(@RequestBody MonitoredService service) {
        service.setEnabled(true);
        service.setHealthStatus(MonitoredService.HealthStatus.UNKNOWN);
        MonitoredService saved = serviceRepository.save(service);
        return ResponseEntity.ok(saved);
    }

    /**
     * List all monitored services.
     */
    @GetMapping("/services")
    public ResponseEntity<List<MonitoredService>> listServices() {
        return ResponseEntity.ok(serviceRepository.findAll());
    }

    /**
     * Get a specific service.
     */
    @GetMapping("/services/{name}")
    public ResponseEntity<MonitoredService> getService(@PathVariable String name) {
        return serviceRepository.findByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Trigger a manual health check for a service.
     */
    @PostMapping("/services/{name}/check")
    public ResponseEntity<Map<String, Object>> checkService(@PathVariable String name) {
        return serviceRepository.findByName(name)
                .map(service -> {
                    var result = monitoringService.checkServiceHealth(service);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "service", result.serviceName(),
                            "healthy", result.healthy(),
                            "message", result.message(),
                            "duration_ms", result.durationMs()
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a metric value (for external metric sources).
     */
    @PostMapping("/metrics")
    public ResponseEntity<Map<String, String>> updateMetric(@RequestBody Map<String, Object> request) {
        String metric = (String) request.get("metric");
        double value = ((Number) request.get("value")).doubleValue();
        anomalyDetector.updateMetric(metric, value);
        return ResponseEntity.ok(Map.of("status", "updated", "metric", metric));
    }

    /**
     * Get current metric values.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Double>> getMetrics() {
        return ResponseEntity.ok(anomalyDetector.getCurrentMetrics());
    }

    /**
     * Update an existing monitored service.
     */
    @PutMapping("/services/{id}")
    public ResponseEntity<MonitoredService> updateService(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {
        return serviceRepository.findById(id)
                .map(existing -> {
                    if (updates.containsKey("name"))
                        existing.setName((String) updates.get("name"));
                    if (updates.containsKey("displayName"))
                        existing.setDisplayName((String) updates.get("displayName"));
                    if (updates.containsKey("type"))
                        existing.setType(MonitoredService.ServiceType.valueOf((String) updates.get("type")));
                    if (updates.containsKey("healthEndpoint"))
                        existing.setHealthEndpoint((String) updates.get("healthEndpoint"));
                    if (updates.containsKey("namespace"))
                        existing.setNamespace((String) updates.get("namespace"));
                    if (updates.containsKey("cluster"))
                        existing.setCluster((String) updates.get("cluster"));
                    if (updates.containsKey("checkIntervalSeconds"))
                        existing.setCheckIntervalSeconds(((Number) updates.get("checkIntervalSeconds")).intValue());
                    if (updates.containsKey("enabled"))
                        existing.setEnabled((Boolean) updates.get("enabled"));
                    if (updates.containsKey("metricsEndpoint"))
                        existing.setMetricsEndpoint((String) updates.get("metricsEndpoint"));
                    if (updates.containsKey("logSource"))
                        existing.setLogSource((String) updates.get("logSource"));
                    return ResponseEntity.ok(serviceRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toggle enable/disable for a monitored service.
     */
    @PatchMapping("/services/{id}/toggle")
    public ResponseEntity<MonitoredService> toggleService(@PathVariable String id) {
        return serviceRepository.findById(id)
                .map(existing -> {
                    existing.setEnabled(!existing.isEnabled());
                    return ResponseEntity.ok(serviceRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a monitored service.
     */
    @DeleteMapping("/services/{id}")
    public ResponseEntity<Map<String, String>> deleteService(@PathVariable String id) {
        serviceRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }
}
