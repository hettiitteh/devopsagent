package com.example.devopsagent.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Routes JSON-RPC method calls to their handlers.
 *
 * This router maps method names
 * to handler functions. Organized by domain:
 * - agent.* → Agent execution
 * - monitor.* → Monitoring operations
 * - incident.* → Incident management
 * - playbook.* → Playbook operations
 * - service.* → Service management
 * - alert.* → Alert rule management
 * - gateway.* → Gateway status/control
 */
@Slf4j
@Component
public class GatewayRpcRouter {

    private final Map<String, BiFunction<Object, GatewaySession, Object>> handlers = new ConcurrentHashMap<>();

    /**
     * Register an RPC method handler.
     */
    public void registerMethod(String method, BiFunction<Object, GatewaySession, Object> handler) {
        handlers.put(method, handler);
        log.debug("Registered RPC method: {}", method);
    }

    /**
     * Route an incoming RPC message to its handler.
     */
    public JsonRpcMessage route(JsonRpcMessage request, GatewaySession session) {
        String method = request.getMethod();
        if (method == null) {
            return JsonRpcMessage.error(request.getId(), -32600, "Invalid request: missing method");
        }

        BiFunction<Object, GatewaySession, Object> handler = handlers.get(method);
        if (handler == null) {
            return JsonRpcMessage.error(request.getId(), -32601, "Method not found: " + method);
        }

        try {
            Object result = handler.apply(request.getParams(), session);
            return JsonRpcMessage.success(request.getId(), result);
        } catch (Exception e) {
            log.error("Error executing RPC method {}: {}", method, e.getMessage(), e);
            return JsonRpcMessage.error(request.getId(), -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * List all registered RPC methods.
     */
    public Map<String, String> listMethods() {
        Map<String, String> methodList = new ConcurrentHashMap<>();
        handlers.keySet().forEach(method -> methodList.put(method, "registered"));
        return methodList;
    }

    public int getMethodCount() {
        return handlers.size();
    }
}
