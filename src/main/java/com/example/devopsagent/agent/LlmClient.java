package com.example.devopsagent.agent;

import com.example.devopsagent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * LLM Client for communicating with language models (OpenAI, Anthropic, etc.)
 * Handles the core AI interaction: sending messages + tool definitions,
 * receiving responses with tool calls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json");

    /**
     * Send a conversation to the LLM and get a response.
     * Supports tool calling (function calling) for the agentic loop.
     */
    public AgentMessage chat(List<AgentMessage> messages, List<AgentTool> availableTools) {
        try {
            String requestBody = buildRequestBody(messages, availableTools);
            log.debug("LLM request with {} messages and {} tools", messages.size(), availableTools.size());

            Request request = new Request.Builder()
                    .url(getApiUrl())
                    .addHeader("Authorization", "Bearer " + properties.getLlm().getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            OkHttpClient clientWithTimeout = httpClient.newBuilder()
                    .readTimeout(properties.getLlm().getTimeoutSeconds(), TimeUnit.SECONDS)
                    .build();

            try (Response response = clientWithTimeout.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("LLM API error: {} - {}", response.code(), errorBody);
                    return AgentMessage.assistant("Error communicating with LLM: " + response.code());
                }

                String responseBody = response.body().string();
                return parseResponse(responseBody);
            }

        } catch (Exception e) {
            log.error("Failed to communicate with LLM", e);
            return AgentMessage.assistant("Error: Failed to communicate with LLM - " + e.getMessage());
        }
    }

    private String getApiUrl() {
        return switch (properties.getLlm().getProvider().toLowerCase()) {
            case "openai" -> OPENAI_API_URL;
            case "anthropic" -> "https://api.anthropic.com/v1/messages";
            default -> OPENAI_API_URL; // Default to OpenAI-compatible endpoint
        };
    }

    private String buildRequestBody(List<AgentMessage> messages, List<AgentTool> tools) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getLlm().getModel());
        root.put("temperature", properties.getLlm().getTemperature());
        root.put("max_tokens", properties.getLlm().getMaxTokens());

        // Build messages array
        ArrayNode messagesArray = root.putArray("messages");
        for (AgentMessage msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.getRole().name().toLowerCase());

            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            }

            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }

            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                ArrayNode toolCallsNode = msgNode.putArray("tool_calls");
                for (AgentMessage.ToolCall tc : msg.getToolCalls()) {
                    ObjectNode tcNode = toolCallsNode.addObject();
                    tcNode.put("id", tc.getId());
                    tcNode.put("type", "function");
                    ObjectNode funcNode = tcNode.putObject("function");
                    funcNode.put("name", tc.getName());
                    funcNode.put("arguments", objectMapper.writeValueAsString(tc.getArguments()));
                }
            }
        }

        // Build tools array
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (AgentTool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode funcNode = toolNode.putObject("function");
                funcNode.put("name", tool.getName());
                funcNode.put("description", tool.getDescription());
                funcNode.set("parameters", objectMapper.valueToTree(tool.getParameterSchema()));
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    private AgentMessage parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            return AgentMessage.assistant("No response from LLM");
        }

        JsonNode message = choices.get(0).get("message");
        String content = message.has("content") && !message.get("content").isNull()
                ? message.get("content").asText() : null;

        // Check for tool calls
        if (message.has("tool_calls") && !message.get("tool_calls").isEmpty()) {
            List<AgentMessage.ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode tc : message.get("tool_calls")) {
                JsonNode function = tc.get("function");
                Map<String, Object> args = objectMapper.readValue(
                        function.get("arguments").asText(), Map.class);
                toolCalls.add(AgentMessage.ToolCall.builder()
                        .id(tc.get("id").asText())
                        .name(function.get("name").asText())
                        .arguments(args)
                        .build());
            }
            return AgentMessage.builder()
                    .role(AgentMessage.Role.ASSISTANT)
                    .content(content)
                    .toolCalls(toolCalls)
                    .build();
        }

        return AgentMessage.assistant(content != null ? content : "");
    }
}
