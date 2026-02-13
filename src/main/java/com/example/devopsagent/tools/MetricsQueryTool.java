package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Metrics Query Tool - Queries Prometheus, application metrics, and custom sources.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsQueryTool implements AgentTool {

    private final WebClient webClient;

    @Override
    public String getName() { return "metrics_query"; }

    @Override
    public String getDescription() {
        return "Query metrics from Prometheus or other metrics backends. " +
               "Supports PromQL queries, instant and range queries. " +
               "Use for investigating performance issues, capacity planning, and anomaly detection.";
    }

    @Override
    public String getCategory() { return "monitoring"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "PromQL query or metric name"),
                "prometheus_url", Map.of("type", "string", "description", "Prometheus server URL", "default", "http://localhost:9090"),
                "type", Map.of("type", "string", "description", "Query type: instant, range", "default", "instant"),
                "start", Map.of("type", "string", "description", "Start time for range queries (ISO 8601 or relative like '1h')"),
                "end", Map.of("type", "string", "description", "End time for range queries"),
                "step", Map.of("type", "string", "description", "Step interval for range queries", "default", "60s")
            ),
            "required", List.of("query")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String query = (String) parameters.get("query");
        String prometheusUrl = (String) parameters.getOrDefault("prometheus_url", "http://localhost:9090");
        String queryType = (String) parameters.getOrDefault("type", "instant");

        try {
            String apiPath = "instant".equals(queryType) ? "/api/v1/query" : "/api/v1/query_range";
            String url = prometheusUrl + apiPath + "?query=" + java.net.URLEncoder.encode(query, "UTF-8");

            if ("range".equals(queryType)) {
                String start = (String) parameters.getOrDefault("start", "");
                String end = (String) parameters.getOrDefault("end", "");
                String step = (String) parameters.getOrDefault("step", "60s");
                url += "&start=" + start + "&end=" + end + "&step=" + step;
            }

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return ToolResult.text(String.format(
                    "Metrics Query Result:\nQuery: %s\nType: %s\nResponse:\n%s",
                    query, queryType, response));

        } catch (Exception e) {
            log.error("Metrics query failed: {}", e.getMessage());
            return ToolResult.text(String.format(
                    "Metrics Query Failed:\nQuery: %s\nError: %s\n\nNote: Ensure Prometheus is accessible at the specified URL.",
                    query, e.getMessage()));
        }
    }
}
