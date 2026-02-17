package com.example.devopsagent.gateway;

import com.example.devopsagent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core Gateway WebSocket Handler - The center of everything.
 *
 * This is the single WebSocket endpoint through which
 * all clients communicate. It manages sessions, routes JSON-RPC messages to
 * the appropriate handlers, and broadcasts events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final GatewayRpcRouter rpcRouter;
    private final AgentProperties properties;

    private final Map<String, GatewaySession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        GatewaySession gatewaySession = GatewaySession.builder()
                .sessionId(session.getId())
                .webSocketSession(session)
                .connectedAt(Instant.now())
                .lastHeartbeat(Instant.now())
                .build();

        sessions.put(session.getId(), gatewaySession);
        log.info("Gateway session connected: {} (total: {})", session.getId(), sessions.size());

        // Send welcome message
        sendNotification(session, "gateway.connected", Map.of(
                "sessionId", session.getId(),
                "version", "0.1.0",
                "capabilities", Map.of(
                        "monitoring", true,
                        "playbooks", true,
                        "incidents", true,
                        "agent", true
                )
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonRpcMessage rpcMessage = objectMapper.readValue(message.getPayload(), JsonRpcMessage.class);

            // Update heartbeat
            GatewaySession gatewaySession = sessions.get(session.getId());
            if (gatewaySession != null) {
                gatewaySession.updateHeartbeat();
            }

            // Handle heartbeat
            if ("heartbeat".equals(rpcMessage.getMethod())) {
                sendResponse(session, JsonRpcMessage.success(rpcMessage.getId(), Map.of(
                        "status", "alive",
                        "timestamp", Instant.now().toString()
                )));
                return;
            }

            // Route to RPC handler
            log.debug("RPC request: method={}, id={}", rpcMessage.getMethod(), rpcMessage.getId());
            JsonRpcMessage response = rpcRouter.route(rpcMessage, gatewaySession);
            if (response != null) {
                sendResponse(session, response);
            }

        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendResponse(session, JsonRpcMessage.error(null, -32700, "Parse error: " + e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("Gateway session disconnected: {} (reason: {}, total: {})",
                session.getId(), status.getReason(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error for session {}: {}", session.getId(), exception.getMessage());
        sessions.remove(session.getId());
    }

    /**
     * Broadcast a notification to all connected sessions.
     */
    public void broadcast(String method, Object params) {
        JsonRpcMessage notification = JsonRpcMessage.notification(method, params);
        sessions.values().forEach(session -> {
            if (session.isAlive()) {
                sendNotification(session.getWebSocketSession(), method, params);
            }
        });
    }

    /**
     * Send a notification to a specific session.
     */
    public void sendToSession(String sessionId, String method, Object params) {
        GatewaySession session = sessions.get(sessionId);
        if (session != null && session.isAlive()) {
            sendNotification(session.getWebSocketSession(), method, params);
        }
    }

    private void sendResponse(WebSocketSession session, JsonRpcMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to send response to session {}", session.getId(), e);
        }
    }

    private void sendNotification(WebSocketSession session, String method, Object params) {
        try {
            JsonRpcMessage notification = JsonRpcMessage.notification(method, params);
            String json = objectMapper.writeValueAsString(notification);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to send notification to session {}", session.getId(), e);
        }
    }

    public Map<String, GatewaySession> getActiveSessions() {
        return Map.copyOf(sessions);
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }
}
