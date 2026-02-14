package com.example.devopsagent.monitoring;

import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.Incident;
import com.example.devopsagent.domain.MonitoredService;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.playbook.PlaybookEngine;
import com.example.devopsagent.repository.IncidentRepository;
import com.example.devopsagent.repository.MonitoredServiceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Core Monitoring Service - Continuously monitors registered services.
 *
 * Like OpenClaw's heartbeat monitoring system, this service:
 * - Polls health endpoints at configured intervals
 * - Tracks service health history
 * - Detects anomalies and state transitions
 * - Creates incidents for detected issues
 * - Broadcasts health updates via the gateway
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final MonitoredServiceRepository serviceRepository;
    private final IncidentRepository incidentRepository;
    private final AgentProperties properties;
    private final OkHttpClient httpClient;
    private final GatewayWebSocketHandler gatewayHandler;
    private final MeterRegistry meterRegistry;
    private final PlaybookEngine playbookEngine;

    // Health check history for anomaly detection
    private final Map<String, List<HealthCheckResult>> healthHistory = new ConcurrentHashMap<>();

    /**
     * Periodic scheduler that ticks every 5 seconds and checks each service
     * only when its individual checkIntervalSeconds has elapsed since the last check.
     * This respects per-service monitoring frequency set via the UI.
     */
    @Scheduled(fixedDelay = 5000)
    public void runHealthChecks() {
        if (!properties.getMonitoring().isEnabled()) return;

        List<MonitoredService> services = serviceRepository.findByEnabled(true);
        if (services.isEmpty()) return;

        Instant now = Instant.now();

        for (MonitoredService service : services) {
            try {
                int intervalSeconds = service.getCheckIntervalSeconds() > 0
                        ? service.getCheckIntervalSeconds() : 60;

                // Check if enough time has passed since the last health check
                if (service.getLastCheckAt() == null ||
                        Duration.between(service.getLastCheckAt(), now).getSeconds() >= intervalSeconds) {
                    log.debug("Health check due for {} (interval: {}s)", service.getName(), intervalSeconds);
                    checkServiceHealth(service);
                }
            } catch (Exception e) {
                log.error("Health check failed for service {}: {}", service.getName(), e.getMessage());
            }
        }
    }

    /**
     * Check a single service's health.
     */
    public HealthCheckResult checkServiceHealth(MonitoredService service) {
        Timer.Sample sample = Timer.start(meterRegistry);
        HealthCheckResult result;

        try {
            result = switch (service.getType()) {
                case HTTP -> checkHttpHealth(service);
                case TCP -> checkTcpHealth(service);
                case KUBERNETES_DEPLOYMENT, KUBERNETES_STATEFULSET -> checkKubernetesHealth(service);
                case DOCKER_CONTAINER -> checkDockerHealth(service);
                default -> new HealthCheckResult(service.getName(), false, "Unsupported service type", 0);
            };
        } catch (Exception e) {
            result = new HealthCheckResult(service.getName(), false, e.getMessage(), 0);
        }

        // Record metrics
        sample.stop(Timer.builder("devops.health_check.duration")
                .tag("service", service.getName())
                .tag("healthy", String.valueOf(result.healthy()))
                .register(meterRegistry));

        Counter.builder("devops.health_check.total")
                .tag("service", service.getName())
                .tag("status", result.healthy() ? "healthy" : "unhealthy")
                .register(meterRegistry)
                .increment();

        // Update service state
        updateServiceState(service, result);

        return result;
    }

    private HealthCheckResult checkHttpHealth(MonitoredService service) {
        if (service.getHealthEndpoint() == null || service.getHealthEndpoint().isEmpty()) {
            return new HealthCheckResult(service.getName(), false, "No health endpoint configured", 0);
        }

        long start = System.currentTimeMillis();
        try {
            OkHttpClient client = httpClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(service.getHealthEndpoint())
                    .build();

            try (Response response = client.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - start;
                boolean healthy = response.isSuccessful();
                String message = String.format("HTTP %d (%dms)", response.code(), duration);
                return new HealthCheckResult(service.getName(), healthy, message, duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new HealthCheckResult(service.getName(), false, e.getMessage(), duration);
        }
    }

    private HealthCheckResult checkTcpHealth(MonitoredService service) {
        // TCP health check via socket connection
        try {
            String endpoint = service.getHealthEndpoint();
            String[] parts = endpoint.replace("tcp://", "").split(":");
            java.net.Socket socket = new java.net.Socket();
            long start = System.currentTimeMillis();
            socket.connect(new java.net.InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 10000);
            long duration = System.currentTimeMillis() - start;
            socket.close();
            return new HealthCheckResult(service.getName(), true, "TCP connected (" + duration + "ms)", duration);
        } catch (Exception e) {
            return new HealthCheckResult(service.getName(), false, "TCP failed: " + e.getMessage(), 0);
        }
    }

    private HealthCheckResult checkKubernetesHealth(MonitoredService service) {
        try {
            String ns = service.getNamespace() != null ? service.getNamespace() : "default";
            ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "deployment", service.getName(),
                    "-n", ns, "-o", "jsonpath={.status.readyReplicas}/{.status.replicas}");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                return new HealthCheckResult(service.getName(), false, "kubectl failed: " + output, 0);
            }

            String[] parts = output.split("/");
            boolean healthy = parts.length == 2 && parts[0].equals(parts[1]) && !parts[0].equals("0");
            return new HealthCheckResult(service.getName(), healthy, "Replicas: " + output, 0);

        } catch (Exception e) {
            return new HealthCheckResult(service.getName(), false, "K8s check failed: " + e.getMessage(), 0);
        }
    }

    private HealthCheckResult checkDockerHealth(MonitoredService service) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "inspect", "--format",
                    "{{.State.Status}}", service.getName());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            boolean healthy = exitCode == 0 && "running".equals(output);
            return new HealthCheckResult(service.getName(), healthy, "Container: " + output, 0);

        } catch (Exception e) {
            return new HealthCheckResult(service.getName(), false, "Docker check failed: " + e.getMessage(), 0);
        }
    }

    /**
     * Update service state and create incidents for state transitions.
     */
    private void updateServiceState(MonitoredService service, HealthCheckResult result) {
        MonitoredService.HealthStatus previousStatus = service.getHealthStatus();
        MonitoredService.HealthStatus newStatus = result.healthy()
                ? MonitoredService.HealthStatus.HEALTHY
                : MonitoredService.HealthStatus.UNHEALTHY;

        service.setLastCheckAt(Instant.now());
        service.setHealthStatus(newStatus);

        if (result.healthy()) {
            service.setConsecutiveFailures(0);
            service.setLastHealthyAt(Instant.now());
        } else {
            service.setConsecutiveFailures(service.getConsecutiveFailures() + 1);
        }

        serviceRepository.save(service);

        // Detect state transition: HEALTHY → UNHEALTHY  or  UNKNOWN → UNHEALTHY
        if ((previousStatus == MonitoredService.HealthStatus.HEALTHY
                || previousStatus == MonitoredService.HealthStatus.UNKNOWN)
                && newStatus == MonitoredService.HealthStatus.UNHEALTHY) {
            log.warn("Service {} transitioned from {} to UNHEALTHY: {}",
                    service.getName(), previousStatus, result.message());
            onServiceUnhealthy(service, result);
        }

        // Detect recovery: UNHEALTHY → HEALTHY
        if (previousStatus == MonitoredService.HealthStatus.UNHEALTHY
                && newStatus == MonitoredService.HealthStatus.HEALTHY) {
            log.info("Service {} recovered: {}", service.getName(), result.message());
            onServiceRecovered(service);
        }

        // Broadcast health update via WebSocket gateway
        gatewayHandler.broadcast("monitor.health_update", Map.of(
                "service", service.getName(),
                "status", newStatus.name(),
                "message", result.message(),
                "timestamp", Instant.now().toString()
        ));
    }

    private void onServiceUnhealthy(MonitoredService service, HealthCheckResult result) {
        String incidentId = null;

        if (properties.getIncidents().isAutoCreate()) {
            Incident incident = Incident.builder()
                    .title("Service unhealthy: " + service.getName())
                    .description(String.format(
                            "Service %s is reporting unhealthy status.\nDetails: %s\nConsecutive failures: %d",
                            service.getName(), result.message(), service.getConsecutiveFailures()))
                    .severity(Incident.Severity.HIGH)
                    .status(Incident.IncidentStatus.OPEN)
                    .service(service.getName())
                    .source("monitoring-service")
                    .build();
            incidentRepository.save(incident);
            incidentId = incident.getId();
            log.info("Auto-created incident {} for unhealthy service: {}", incidentId, service.getName());

            // Broadcast incident via gateway
            gatewayHandler.broadcast("incident.created", Map.of(
                    "incident_id", incident.getId(),
                    "title", incident.getTitle(),
                    "severity", incident.getSeverity().name(),
                    "service", service.getName()
            ));
        }

        // Auto-trigger matching playbooks
        try {
            playbookEngine.autoTriggerPlaybooks(
                    service.getName(),
                    Incident.Severity.HIGH.name(),
                    incidentId
            );
        } catch (Exception e) {
            log.error("Failed to auto-trigger playbooks for service {}: {}", service.getName(), e.getMessage());
        }
    }

    private void onServiceRecovered(MonitoredService service) {
        // Auto-resolve related open incidents
        List<Incident> openIncidents = incidentRepository.findByServiceAndStatusIn(
                service.getName(),
                List.of(Incident.IncidentStatus.OPEN, Incident.IncidentStatus.INVESTIGATING));

        for (Incident incident : openIncidents) {
            if ("monitoring-service".equals(incident.getSource())) {
                incident.setStatus(Incident.IncidentStatus.RESOLVED);
                incident.setResolvedAt(Instant.now());
                incident.setResolution("Service recovered automatically.");
                incidentRepository.save(incident);
                log.info("Auto-resolved incident {} for recovered service {}", incident.getId(), service.getName());
            }
        }

        gatewayHandler.broadcast("monitor.service_recovered", Map.of(
                "service", service.getName(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Get health status summary for all services.
     */
    public Map<String, Object> getHealthSummary() {
        List<MonitoredService> all = serviceRepository.findAll();
        long healthy = all.stream().filter(s -> s.getHealthStatus() == MonitoredService.HealthStatus.HEALTHY).count();
        long unhealthy = all.stream().filter(s -> s.getHealthStatus() == MonitoredService.HealthStatus.UNHEALTHY).count();
        long unknown = all.stream().filter(s -> s.getHealthStatus() == MonitoredService.HealthStatus.UNKNOWN).count();

        return Map.of(
                "total", all.size(),
                "healthy", healthy,
                "unhealthy", unhealthy,
                "unknown", unknown,
                "services", all.stream().map(s -> Map.of(
                        "name", s.getName(),
                        "status", s.getHealthStatus().name(),
                        "lastCheck", s.getLastCheckAt() != null ? s.getLastCheckAt().toString() : "never",
                        "consecutiveFailures", s.getConsecutiveFailures()
                )).toList()
        );
    }

    public record HealthCheckResult(String serviceName, boolean healthy, String message, long durationMs) {}
}
