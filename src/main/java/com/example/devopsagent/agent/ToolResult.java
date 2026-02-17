package com.example.devopsagent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result from a tool execution.
 * Supports text and structured data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    @Builder.Default
    private boolean success = true;

    @Builder.Default
    private List<ContentBlock> content = new ArrayList<>();

    private String error;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentBlock {
        private String type; // "text", "json", "table", "metric"
        private String text;
        private Object data;
    }

    public static ToolResult text(String text) {
        return ToolResult.builder()
                .success(true)
                .content(List.of(ContentBlock.builder().type("text").text(text).build()))
                .build();
    }

    public static ToolResult json(Object data) {
        return ToolResult.builder()
                .success(true)
                .content(List.of(ContentBlock.builder().type("json").data(data).build()))
                .build();
    }

    public static ToolResult error(String errorMessage) {
        return ToolResult.builder()
                .success(false)
                .error(errorMessage)
                .content(List.of(ContentBlock.builder().type("text").text("Error: " + errorMessage).build()))
                .build();
    }

    public String getTextContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block.getText() != null) {
                sb.append(block.getText()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
