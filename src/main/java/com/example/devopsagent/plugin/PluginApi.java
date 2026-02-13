package com.example.devopsagent.plugin;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.gateway.GatewayRpcRouter;
import com.example.devopsagent.gateway.GatewaySession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.BiFunction;

/**
 * Plugin API - The stable surface area for plugins to interact with the agent system.
 *
 * Like OpenClaw's OpenClawPluginApi, this is the only API plugins should use.
 * Internal types are not guaranteed stable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginApi {

    private final ToolRegistry toolRegistry;
    private final GatewayRpcRouter rpcRouter;

    /**
     * Register a new tool.
     */
    public void registerTool(AgentTool tool) {
        toolRegistry.register(tool);
        log.info("Plugin registered tool: {}", tool.getName());
    }

    /**
     * Unregister a tool.
     */
    public void unregisterTool(String toolName) {
        toolRegistry.unregister(toolName);
    }

    /**
     * Register a new Gateway RPC method.
     */
    public void registerGatewayMethod(String method, BiFunction<Object, GatewaySession, Object> handler) {
        rpcRouter.registerMethod(method, handler);
        log.info("Plugin registered gateway method: {}", method);
    }

    /**
     * Register an event hook.
     */
    public void onEvent(String eventName, Runnable handler) {
        // Event system - extensible
        log.info("Plugin registered event hook: {}", eventName);
    }
}
