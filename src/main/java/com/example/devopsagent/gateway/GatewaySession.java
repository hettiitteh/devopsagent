package com.example.devopsagent.gateway;

import lombok.Builder;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a connected gateway session.
 * Tracks client connections, their state, and metadata.
 */
@Data
@Builder
public class GatewaySession {

    private final String sessionId;
    private final WebSocketSession webSocketSession;
    private final Instant connectedAt;
    private Instant lastHeartbeat;
    private String clientType;
    private String clientVersion;
    private boolean authenticated;

    @Builder.Default
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    public boolean isAlive() {
        return webSocketSession != null && webSocketSession.isOpen();
    }

    public boolean isStale(int timeoutSeconds) {
        if (lastHeartbeat == null) return false;
        return Instant.now().isAfter(lastHeartbeat.plusSeconds(timeoutSeconds));
    }
}
