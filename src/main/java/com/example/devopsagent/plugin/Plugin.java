package com.example.devopsagent.plugin;

import com.example.devopsagent.agent.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * Plugin Interface - Extensibility point for the DevOps Agent.
 *
 * Like OpenClaw's plugin system, plugins can register:
 * - Tools: New agent capabilities
 * - Hooks: Lifecycle event handlers
 * - Channels: New notification channels
 * - Gateway methods: New RPC endpoints
 */
public interface Plugin {

    /**
     * Unique plugin identifier.
     */
    String getId();

    /**
     * Human-readable plugin name.
     */
    String getName();

    /**
     * Plugin version.
     */
    String getVersion();

    /**
     * Plugin description.
     */
    String getDescription();

    /**
     * Register the plugin with the agent system.
     */
    void register(PluginApi api);

    /**
     * Called when the plugin is activated.
     */
    default void activate() {}

    /**
     * Called when the plugin is deactivated.
     */
    default void deactivate() {}

    /**
     * Plugin configuration schema (JSON Schema).
     */
    default Map<String, Object> getConfigSchema() {
        return Map.of();
    }
}
