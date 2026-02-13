package com.example.devopsagent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a message in the agent conversation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    private Role role;
    private String content;
    private List<ToolCall> toolCalls;
    private String toolCallId;
    private Instant timestamp;

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }

    public static AgentMessage system(String content) {
        return AgentMessage.builder()
                .role(Role.SYSTEM)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    public static AgentMessage user(String content) {
        return AgentMessage.builder()
                .role(Role.USER)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    public static AgentMessage assistant(String content) {
        return AgentMessage.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    public static AgentMessage toolResult(String toolCallId, String content) {
        return AgentMessage.builder()
                .role(Role.TOOL)
                .toolCallId(toolCallId)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }
}
