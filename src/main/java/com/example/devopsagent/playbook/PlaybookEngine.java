package com.example.devopsagent.playbook;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.agent.ToolResult;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.domain.PlaybookDefinition;
import com.example.devopsagent.domain.PlaybookExecution;
import com.example.devopsagent.gateway.GatewayWebSocketHandler;
import com.example.devopsagent.repository.PlaybookDefinitionRepository;
import com.example.devopsagent.repository.PlaybookExecutionRepository;
import com.example.devopsagent.service.AuditService;
import com.example.devopsagent.service.AutonomousInvestigationService;
import com.example.devopsagent.service.LearningService;
import com.example.devopsagent.service.NarrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Playbook Engine - Loads and executes YAML-based remediation runbooks.
 *
 * Features:
 * - YAML playbook definitions with ordered steps
 * - Each step maps to an agent tool
 * - Conditional execution, retries, and failure handling
 * - Dry-run support
 * - Execution tracking and history
 * - Auto-trigger based on incidents and alerts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybookEngine {

    private final ToolRegistry toolRegistry;
    private final PlaybookExecutionRepository executionRepository;
    private final PlaybookDefinitionRepository definitionRepository;
    private final AgentProperties properties;
    private final GatewayWebSocketHandler gatewayHandler;
    private final AuditService auditService;
    private final LearningService learningService;
    private final NarrationService narrationService;
    private final AutonomousInvestigationService autonomousInvestigationService;

    private final Map<String, Playbook> playbooks = new ConcurrentHashMap<>();
    private final Map<String, PlaybookExecution> runningExecutions = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void init() {
        loadPlaybooks();
    }

    /**
     * Load playbooks from the database.
     * On first boot (empty DB), migrates existing YAML files from disk.
     */
    public void loadPlaybooks() {
        // Migration: if the DB is empty, import YAML files from disk
        if (definitionRepository.count() == 0) {
            migrateYamlToDb();
        }

        // Load enabled playbooks from DB
        playbooks.clear();
        List<PlaybookDefinition> definitions = definitionRepository.findByEnabledTrue();
        for (PlaybookDefinition def : definitions) {
            try {
                Playbook playbook = def.toPlaybook();
                playbooks.put(playbook.getId(), playbook);
                log.debug("Loaded playbook from DB: {} ({} steps)", playbook.getName(),
                        playbook.getSteps() != null ? playbook.getSteps().size() : 0);
            } catch (Exception e) {
                log.error("Failed to load playbook definition {}: {}", def.getId(), e.getMessage());
            }
        }

        log.info("Loaded {} playbooks from database", playbooks.size());
    }

    /**
     * One-time migration: reads existing YAML files from the playbooks directory,
     * converts each to a PlaybookDefinition, and saves to the database.
     * After migration, YAML files remain on disk as backup but are never read again.
     */
    private void migrateYamlToDb() {
        String directory = properties.getPlaybooks().getDirectory();
        File dir = new File(directory);

        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created playbooks directory: {}", directory);
            // Create sample YAML playbooks first, then migrate them
            createSamplePlaybooks(dir);
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            log.info("No YAML playbooks found to migrate in {}", directory);
            createSamplePlaybooks(dir);
            // Re-scan after creating samples
            files = dir.listFiles((d, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
            if (files == null || files.length == 0) return;
        }

        int migrated = 0;
        for (File file : files) {
            try {
                Playbook playbook = yamlMapper.readValue(file, Playbook.class);
                if (playbook.getId() == null) {
                    playbook.setId(file.getName().replace(".yml", "").replace(".yaml", ""));
                }

                // Migrate single severity to severities list for any triggers
                if (playbook.getTriggers() != null) {
                    for (Playbook.TriggerCondition trigger : playbook.getTriggers()) {
                        if (trigger.getSeverities() == null) {
                            trigger.setSeverities(new ArrayList<>());
                        }
                        // The @JsonSetter("severity") handles the old format during deserialization,
                        // but if severities is still empty and we want a default, add "*"
                        if (trigger.getSeverities().isEmpty()) {
                            trigger.getSeverities().add("*");
                        }
                    }
                }

                PlaybookDefinition def = PlaybookDefinition.fromPlaybook(playbook, "yaml-import");
                def.setEnabled(true);
                definitionRepository.save(def);
                migrated++;
                log.info("Migrated playbook '{}' from YAML to database", playbook.getName());
            } catch (IOException e) {
                log.error("Failed to migrate playbook {}: {}", file.getName(), e.getMessage());
            }
        }

        log.info("Migrated {} playbooks from YAML to database", migrated);
    }

    /**
     * Execute a playbook synchronously. Used by PlaybookRunTool and other callers
     * that need the result immediately.
     */
    public ToolResult executePlaybook(String playbookId, String incidentId,
                                       Map<String, Object> parameters, boolean dryRun) {
        return doExecutePlaybook(playbookId, incidentId, parameters, dryRun);
    }

    /**
     * Execute a playbook asynchronously (fire-and-forget).
     * Used by autoTriggerPlaybooks and the REST controller where we don't need
     * to wait for the result.
     */
    @Async("playbookExecutor")
    public void executePlaybookAsync(String playbookId, String incidentId,
                                      Map<String, Object> parameters, boolean dryRun) {
        doExecutePlaybook(playbookId, incidentId, parameters, dryRun);
    }

    /**
     * Internal playbook execution logic shared by sync and async entry points.
     */
    private ToolResult doExecutePlaybook(String playbookId, String incidentId,
                                          Map<String, Object> parameters, boolean dryRun) {
        Playbook playbook = playbooks.get(playbookId);
        if (playbook == null) {
            return ToolResult.error("Playbook not found: " + playbookId);
        }

        if (playbook.isApprovalRequired() && !dryRun) {
            // Check if approval has been granted (for now, log warning)
            log.warn("Playbook {} requires approval before execution", playbookId);
        }

        // Create execution record
        PlaybookExecution execution = PlaybookExecution.builder()
                .playbookId(playbookId)
                .playbookName(playbook.getName())
                .incidentId(incidentId)
                .status(dryRun ? PlaybookExecution.ExecutionStatus.PENDING : PlaybookExecution.ExecutionStatus.RUNNING)
                .triggeredBy("sre-agent")
                .totalSteps(playbook.getSteps().size())
                .startedAt(Instant.now())
                .build();
        execution = executionRepository.save(execution);
        runningExecutions.put(execution.getId(), execution);

        log.info("{}Executing playbook: {} ({} steps)",
                dryRun ? "[DRY RUN] " : "", playbook.getName(), playbook.getSteps().size());

        // Audit: playbook started
        auditService.log("playbook-engine", "PLAYBOOK_RUN", playbookId,
                Map.of("name", playbook.getName(), "steps", playbook.getSteps().size(),
                       "dry_run", dryRun, "incident_id", incidentId != null ? incidentId : ""));

        StringBuilder output = new StringBuilder();
        output.append(dryRun ? "=== DRY RUN ===" : "=== EXECUTING ===").append("\n");
        output.append("Playbook: ").append(playbook.getName()).append("\n");
        output.append("Steps: ").append(playbook.getSteps().size()).append("\n\n");

        // Create tool context
        ToolContext toolContext = ToolContext.builder()
                .sessionId(execution.getId())
                .toolProfile(properties.getToolPolicy().getDefaultProfile())
                .allowedTools(Set.of("*"))
                .dryRun(dryRun)
                .approvalGranted(!playbook.isApprovalRequired())
                .build();

        boolean success = true;
        List<String> toolsExecuted = new ArrayList<>();
        for (Playbook.Step step : playbook.getSteps()) {
            execution.setCurrentStep(step.getOrder());
            executionRepository.save(execution);

            output.append(String.format("Step %d: %s\n", step.getOrder(), step.getName()));

            if (dryRun) {
                output.append(String.format("  Tool: %s\n  Parameters: %s\n  [Would execute]\n\n",
                        step.getTool(), step.getParameters()));
                continue;
            }

            // Broadcast step progress
            gatewayHandler.broadcast("playbook.step_started", Map.of(
                    "execution_id", execution.getId(),
                    "step", step.getOrder(),
                    "step_name", step.getName(),
                    "total_steps", playbook.getSteps().size()
            ));

            // Execute the step
            ToolResult stepResult = executeStep(step, toolContext, parameters);
            toolsExecuted.add(step.getTool());
            output.append("  Result: ").append(stepResult.getTextContent()).append("\n\n");

            // Audit: step completed
            auditService.log("playbook-engine", "PLAYBOOK_STEP", playbookId,
                    Map.of("step", step.getOrder(), "step_name", step.getName(),
                           "tool", step.getTool(), "success", stepResult.isSuccess()));

            if (!stepResult.isSuccess()) {
                String onFailure = step.getOnFailure() != null ? step.getOnFailure() : "abort";
                switch (onFailure) {
                    case "continue" -> {
                        output.append("  [Step failed, continuing...]\n\n");
                        log.warn("Step {} failed, continuing: {}", step.getName(), stepResult.getError());
                    }
                    case "retry" -> {
                        boolean retrySuccess = false;
                        for (int retry = 0; retry < step.getMaxRetries(); retry++) {
                            output.append(String.format("  [Retry %d/%d]\n", retry + 1, step.getMaxRetries()));
                            try {
                                Thread.sleep(step.getRetryDelaySeconds() * 1000L);
                            } catch (InterruptedException ignored) {}
                            stepResult = executeStep(step, toolContext, parameters);
                            if (stepResult.isSuccess()) {
                                retrySuccess = true;
                                break;
                            }
                        }
                        if (!retrySuccess) {
                            output.append("  [All retries failed, aborting]\n\n");
                            success = false;
                        }
                    }
                    default -> { // abort
                        output.append("  [Step failed, aborting playbook]\n\n");
                        success = false;
                    }
                }
                if (!success) break;
            }
        }

        // Update execution record
        execution.setStatus(success ? PlaybookExecution.ExecutionStatus.SUCCESS : PlaybookExecution.ExecutionStatus.FAILED);
        execution.setCompletedAt(Instant.now());
        execution.setOutput(output.toString());
        execution.setExecutionTimeMs(
                execution.getCompletedAt().toEpochMilli() - execution.getStartedAt().toEpochMilli());
        executionRepository.save(execution);
        runningExecutions.remove(execution.getId());

        gatewayHandler.broadcast("playbook.completed", Map.of(
                "execution_id", execution.getId(),
                "playbook", playbook.getName(),
                "status", execution.getStatus().name(),
                "duration_ms", execution.getExecutionTimeMs()
        ));

        // Narrate the playbook completion via LLM
        narrationService.narratePlaybookCompleted(
                playbook.getName(), success, execution.getExecutionTimeMs(), output.toString());

        // On failure, launch autonomous investigation
        if (!success && !dryRun) {
            String serviceName = parameters != null && parameters.get("service_name") != null
                    ? parameters.get("service_name").toString() : playbookId;
            autonomousInvestigationService.investigatePlaybookFailure(
                    playbook.getName(), serviceName, output.toString());
        }

        // Audit: playbook completed
        auditService.log("playbook-engine", "PLAYBOOK_COMPLETED", playbookId,
                Map.of("name", playbook.getName(), "status", execution.getStatus().name(),
                       "duration_ms", execution.getExecutionTimeMs()),
                null, success);

        // Record resolution for learning — captures the playbook's tool sequence
        if (!dryRun && !toolsExecuted.isEmpty()) {
            try {
                // Determine service name from parameters or playbook metadata
                String serviceName = parameters != null && parameters.get("service_name") != null
                        ? parameters.get("service_name").toString() : playbookId;

                // Prefix with "playbook:" to distinguish from chat-driven resolutions
                List<String> labeledTools = new ArrayList<>();
                labeledTools.add("playbook:" + playbookId);
                labeledTools.addAll(toolsExecuted);
                learningService.recordResolution(
                        incidentId,
                        serviceName,
                        "Playbook: " + playbook.getName(),
                        labeledTools,
                        success,
                        execution.getExecutionTimeMs());
            } catch (Exception e) {
                log.warn("Failed to record playbook resolution for learning: {}", e.getMessage());
            }
        }

        return ToolResult.text(output.toString());
    }

    private ToolResult executeStep(Playbook.Step step, ToolContext toolContext, Map<String, Object> params) {
        Optional<AgentTool> tool = toolRegistry.getTool(step.getTool());
        if (tool.isEmpty()) {
            return ToolResult.error("Tool not found: " + step.getTool());
        }

        // Merge playbook variables with step parameters
        Map<String, Object> mergedParams = new HashMap<>(step.getParameters() != null ? step.getParameters() : Map.of());
        if (params != null) {
            // Replace variable placeholders
            mergedParams.forEach((key, value) -> {
                if (value instanceof String strVal && strVal.startsWith("${") && strVal.endsWith("}")) {
                    String varName = strVal.substring(2, strVal.length() - 1);
                    if (params.containsKey(varName)) {
                        mergedParams.put(key, params.get(varName));
                    }
                }
            });
            // Also inject playbook-level params for keys not already in the step params.
            // This allows auto-triggered playbooks to pass context (e.g. service_name)
            // even when step parameters don't define explicit placeholders.
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                mergedParams.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        try {
            return tool.get().execute(mergedParams, toolContext);
        } catch (Exception e) {
            return ToolResult.error("Step execution failed: " + e.getMessage());
        }
    }

    /**
     * List all available playbooks.
     */
    public ToolResult listPlaybooks() {
        if (playbooks.isEmpty()) {
            return ToolResult.text("No playbooks available. Add .yml files to the playbooks directory.");
        }
        StringBuilder sb = new StringBuilder("Available Playbooks:\n");
        for (Playbook pb : playbooks.values()) {
            sb.append(String.format("- %s: %s (%d steps, approval: %s)\n",
                    pb.getId(), pb.getName(), pb.getSteps().size(), pb.isApprovalRequired()));
            if (pb.getDescription() != null) {
                sb.append("  ").append(pb.getDescription()).append("\n");
            }
        }
        return ToolResult.text(sb.toString());
    }

    /**
     * Get the status of a playbook execution.
     */
    public ToolResult getExecutionStatus(String executionId) {
        return executionRepository.findById(executionId)
                .map(exec -> ToolResult.text(String.format(
                        "Playbook Execution Status:\nID: %s\nPlaybook: %s\nStatus: %s\nStep: %d/%d\nStarted: %s\nCompleted: %s",
                        exec.getId(), exec.getPlaybookName(), exec.getStatus(),
                        exec.getCurrentStep(), exec.getTotalSteps(),
                        exec.getStartedAt(), exec.getCompletedAt())))
                .orElse(ToolResult.error("Execution not found: " + executionId));
    }

    /**
     * Abort a running playbook execution.
     */
    public ToolResult abortExecution(String executionId) {
        PlaybookExecution execution = runningExecutions.get(executionId);
        if (execution == null) {
            return ToolResult.error("No running execution found: " + executionId);
        }
        execution.setStatus(PlaybookExecution.ExecutionStatus.CANCELLED);
        execution.setCompletedAt(Instant.now());
        executionRepository.save(execution);
        runningExecutions.remove(executionId);
        return ToolResult.text("Playbook execution " + executionId + " aborted.");
    }

    /**
     * Find playbooks whose trigger conditions match the given service and severity.
     * Used by the auto-trigger system when incidents are created.
     */
    public List<Playbook> findMatchingPlaybooks(String serviceName, String severity) {
        List<Playbook> matched = new ArrayList<>();
        for (Playbook pb : playbooks.values()) {
            if (pb.getTriggers() == null || pb.getTriggers().isEmpty()) continue;
            for (Playbook.TriggerCondition trigger : pb.getTriggers()) {
                if (matchesTrigger(trigger, serviceName, severity)) {
                    matched.add(pb);
                    break; // one match is enough for this playbook
                }
            }
        }
        return matched;
    }

    private boolean matchesTrigger(Playbook.TriggerCondition trigger, String serviceName, String severity) {
        if (trigger.getType() == null) return false;

        return switch (trigger.getType()) {
            case "service_unhealthy" -> {
                boolean serviceMatch = trigger.getService() == null
                        || trigger.getService().equals("*")
                        || trigger.getService().equalsIgnoreCase(serviceName);
                // For service_unhealthy, any severity qualifies — the service being down
                // is reason enough to trigger. The severities list is informational.
                yield serviceMatch;
            }
            case "incident_severity" -> {
                // Multi-severity match: fire if the incident's severity is in the trigger's list
                List<String> severities = trigger.getSeverities();
                yield severities != null && !severities.isEmpty()
                        && (severities.contains("*")
                            || severities.stream().anyMatch(s -> s.equalsIgnoreCase(severity)));
            }
            default -> false;
        };
    }

    /**
     * Auto-trigger matching playbooks for a service incident.
     * Called by MonitoringService when an incident is created and auto-execute is enabled.
     * Overload for backward compatibility (no serviceType).
     */
    public void autoTriggerPlaybooks(String serviceName, String severity, String incidentId) {
        autoTriggerPlaybooks(serviceName, severity, incidentId, "docker");
    }

    /**
     * Auto-trigger matching playbooks for a service incident.
     * Called by MonitoringService when an incident is created and auto-execute is enabled.
     *
     * @param serviceType the restart type for the service (docker, kubernetes, systemd)
     */
    public void autoTriggerPlaybooks(String serviceName, String severity, String incidentId, String serviceType) {
        if (!properties.getPlaybooks().isAutoExecute()) {
            log.debug("Playbook auto-execute is disabled, skipping auto-trigger for service: {}", serviceName);
            return;
        }

        List<Playbook> matching = findMatchingPlaybooks(serviceName, severity);
        if (matching.isEmpty()) {
            log.info("No matching playbooks found for service '{}' with severity '{}'", serviceName, severity);
            return;
        }

        for (Playbook pb : matching) {
            if (pb.isApprovalRequired()) {
                log.info("Playbook '{}' matches but requires approval — skipping auto-execute", pb.getName());
                gatewayHandler.broadcast("playbook.auto_trigger_skipped", Map.of(
                        "playbook_id", pb.getId(),
                        "playbook_name", pb.getName(),
                        "reason", "approval_required",
                        "service", serviceName,
                        "incident_id", incidentId != null ? incidentId : ""
                ));
                continue;
            }

            log.info("Auto-triggering playbook '{}' for service '{}' (incident: {})",
                    pb.getName(), serviceName, incidentId);

            gatewayHandler.broadcast("playbook.auto_triggered", Map.of(
                    "playbook_id", pb.getId(),
                    "playbook_name", pb.getName(),
                    "service", serviceName,
                    "incident_id", incidentId != null ? incidentId : ""
            ));

            // Narrate the auto-trigger via LLM
            narrationService.narratePlaybookTriggered(pb.getName(), serviceName, incidentId);

            // Execute the playbook asynchronously (not a dry run)
            // Include service_type so playbook steps with empty params can use it
            executePlaybook(pb.getId(), incidentId, Map.of(
                    "service_name", serviceName,
                    "service_type", serviceType,
                    "service_url", "http://localhost:9090" // will be overridden by step params if set
            ), false);
        }
    }

    /**
     * Get a playbook by ID.
     */
    public Optional<Playbook> getPlaybook(String playbookId) {
        return Optional.ofNullable(playbooks.get(playbookId));
    }

    /**
     * Get all loaded playbooks as a collection.
     */
    public Collection<Playbook> getAllPlaybooks() {
        return playbooks.values();
    }

    /**
     * Expose the definition repository for controllers that need direct DB access
     * (e.g. edit, toggle, list with full data).
     */
    public PlaybookDefinitionRepository getDefinitionRepository() {
        return definitionRepository;
    }

    /**
     * Create sample playbooks for demonstration.
     */
    private void createSamplePlaybooks(File dir) {
        try {
            // Service restart playbook
            Playbook restartPlaybook = Playbook.builder()
                    .id("service-restart")
                    .name("Service Restart Playbook")
                    .description("Safely restart an unhealthy service with health verification")
                    .version("1.0")
                    .author("sre-agent")
                    .approvalRequired(true)
                    .maxExecutionTimeSeconds(300)
                    .tags(List.of("restart", "remediation"))
                    .steps(List.of(
                            Playbook.Step.builder()
                                    .order(1).name("Check current health")
                                    .tool("health_check")
                                    .parameters(Map.of("url", "${service_url}"))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(2).name("Collect pre-restart logs")
                                    .tool("log_search")
                                    .parameters(Map.of("source", "${log_source}", "target", "${service_name}", "lines", 50))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(3).name("Restart service")
                                    .tool("service_restart")
                                    .parameters(Map.of("service_type", "${service_type}", "service_name", "${service_name}", "graceful", true))
                                    .onFailure("abort").build(),
                            Playbook.Step.builder()
                                    .order(4).name("Wait and verify health")
                                    .tool("health_check")
                                    .parameters(Map.of("url", "${service_url}", "timeout_seconds", 30))
                                    .onFailure("retry").maxRetries(3).retryDelaySeconds(10).build()
                    ))
                    .build();

            yamlMapper.writeValue(new File(dir, "service-restart.yml"), restartPlaybook);

            // High CPU investigation playbook
            Playbook cpuPlaybook = Playbook.builder()
                    .id("high-cpu-investigation")
                    .name("High CPU Investigation Playbook")
                    .description("Investigate and mitigate high CPU usage on a service")
                    .version("1.0")
                    .author("sre-agent")
                    .approvalRequired(false)
                    .maxExecutionTimeSeconds(600)
                    .tags(List.of("cpu", "performance", "investigation"))
                    .steps(List.of(
                            Playbook.Step.builder()
                                    .order(1).name("Check system resources")
                                    .tool("system_info")
                                    .parameters(Map.of("command", "top"))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(2).name("Check memory usage")
                                    .tool("system_info")
                                    .parameters(Map.of("command", "free"))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(3).name("Check disk usage")
                                    .tool("system_info")
                                    .parameters(Map.of("command", "df"))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(4).name("Check recent error logs")
                                    .tool("log_search")
                                    .parameters(Map.of("source", "${log_source}", "target", "${service_name}", "pattern", "ERROR|Exception|OOM", "lines", 50))
                                    .onFailure("continue").build()
                    ))
                    .build();

            yamlMapper.writeValue(new File(dir, "high-cpu-investigation.yml"), cpuPlaybook);

            // Pod crashloop investigation
            Playbook crashloopPlaybook = Playbook.builder()
                    .id("k8s-crashloop-fix")
                    .name("Kubernetes CrashLoopBackOff Fix")
                    .description("Investigate and fix pods in CrashLoopBackOff state")
                    .version("1.0")
                    .author("sre-agent")
                    .approvalRequired(true)
                    .tags(List.of("kubernetes", "crashloop", "remediation"))
                    .steps(List.of(
                            Playbook.Step.builder()
                                    .order(1).name("Get pod status")
                                    .tool("kubectl_exec")
                                    .parameters(Map.of("command", "get pods", "namespace", "${namespace}"))
                                    .onFailure("abort").build(),
                            Playbook.Step.builder()
                                    .order(2).name("Describe failing pod")
                                    .tool("kubectl_exec")
                                    .parameters(Map.of("command", "describe pod ${pod_name}", "namespace", "${namespace}"))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(3).name("Get pod logs")
                                    .tool("log_search")
                                    .parameters(Map.of("source", "kubectl", "target", "${pod_name}", "lines", 100))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(4).name("Check events")
                                    .tool("kubectl_exec")
                                    .parameters(Map.of("command", "get events --sort-by=.lastTimestamp", "namespace", "${namespace}"))
                                    .onFailure("continue").build(),
                            Playbook.Step.builder()
                                    .order(5).name("Rollout restart deployment")
                                    .tool("kubectl_exec")
                                    .parameters(Map.of("command", "rollout restart deployment/${deployment_name}", "namespace", "${namespace}"))
                                    .onFailure("abort").build()
                    ))
                    .build();

            yamlMapper.writeValue(new File(dir, "k8s-crashloop-fix.yml"), crashloopPlaybook);

            log.info("Created 3 sample playbooks in {}", dir.getAbsolutePath());

        } catch (IOException e) {
            log.error("Failed to create sample playbooks: {}", e.getMessage());
        }
    }
}
