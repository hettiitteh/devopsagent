package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.service.ToolConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers all built-in tools at application startup and syncs
 * their configuration to the database via ToolConfigService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistrationConfig {

    private final ToolRegistry toolRegistry;
    private final List<AgentTool> allTools;
    private final ToolConfigService toolConfigService;

    @EventListener(ApplicationReadyEvent.class)
    public void registerTools() {
        log.info("Registering {} built-in tools...", allTools.size());

        for (AgentTool tool : allTools) {
            toolRegistry.register(tool);
        }

        // Sync tool configs to the database (creates rows for new tools, preserves user edits)
        toolConfigService.syncTools(allTools);

        log.info("Tool registration complete. {} tools available:", toolRegistry.getToolCount());
        toolRegistry.listTools().forEach((name, desc) ->
                log.info("  - {}: {}", name, desc));
    }
}
