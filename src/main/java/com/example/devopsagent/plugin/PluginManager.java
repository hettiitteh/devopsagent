package com.example.devopsagent.plugin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin Manager - Discovers, loads, and manages plugins.
 *
 * Plugin loading flow:
 * 1. Discover plugins from bundled + user extensions
 * 2. Validate configuration
 * 3. Call register() on each plugin
 * 4. Track registered tools/hooks/channels
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginManager {

    private final PluginApi pluginApi;
    private final List<Plugin> discoveredPlugins;

    private final Map<String, Plugin> activePlugins = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadPlugins();
    }

    /**
     * Load and register all discovered plugins.
     */
    public void loadPlugins() {
        if (discoveredPlugins == null || discoveredPlugins.isEmpty()) {
            log.info("No plugins discovered");
            return;
        }

        log.info("Loading {} plugins...", discoveredPlugins.size());

        for (Plugin plugin : discoveredPlugins) {
            try {
                plugin.register(pluginApi);
                plugin.activate();
                activePlugins.put(plugin.getId(), plugin);
                log.info("Loaded plugin: {} v{} - {}", plugin.getName(), plugin.getVersion(), plugin.getDescription());
            } catch (Exception e) {
                log.error("Failed to load plugin {}: {}", plugin.getId(), e.getMessage());
            }
        }

        log.info("Plugin loading complete. {} active plugins.", activePlugins.size());
    }

    /**
     * Get all active plugins.
     */
    public Map<String, Plugin> getActivePlugins() {
        return Map.copyOf(activePlugins);
    }

    /**
     * Deactivate a plugin.
     */
    public void deactivatePlugin(String pluginId) {
        Plugin plugin = activePlugins.remove(pluginId);
        if (plugin != null) {
            plugin.deactivate();
            log.info("Deactivated plugin: {}", pluginId);
        }
    }

    /**
     * List plugin info.
     */
    public List<Map<String, String>> listPlugins() {
        List<Map<String, String>> pluginList = new ArrayList<>();
        activePlugins.values().forEach(p -> pluginList.add(Map.of(
                "id", p.getId(),
                "name", p.getName(),
                "version", p.getVersion(),
                "description", p.getDescription()
        )));
        return pluginList;
    }
}
