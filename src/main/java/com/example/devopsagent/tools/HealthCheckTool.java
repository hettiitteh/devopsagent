package com.example.devopsagent.tools;

import com.example.devopsagent.agent.AgentTool;
import com.example.devopsagent.agent.ToolContext;
import com.example.devopsagent.agent.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Health Check Tool - Probes HTTP endpoints, TCP ports, and services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckTool implements AgentTool {

    private final OkHttpClient httpClient;

    @Override
    public String getName() { return "health_check"; }

    @Override
    public String getDescription() {
        return "Check the health of a service by probing its HTTP endpoint or TCP port. " +
               "Returns status code, response time, and body for HTTP checks. " +
               "Use for verifying service availability and detecting outages.";
    }

    @Override
    public String getCategory() { return "monitoring"; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "url", Map.of("type", "string", "description", "HTTP URL or tcp://host:port to check"),
                "method", Map.of("type", "string", "description", "HTTP method (GET, HEAD)", "default", "GET"),
                "timeout_seconds", Map.of("type", "integer", "description", "Timeout in seconds", "default", 10),
                "expected_status", Map.of("type", "integer", "description", "Expected HTTP status code", "default", 200)
            ),
            "required", java.util.List.of("url")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters, ToolContext context) {
        String url = (String) parameters.get("url");
        int timeout = parameters.containsKey("timeout_seconds")
                ? ((Number) parameters.get("timeout_seconds")).intValue() : 10;

        if (url.startsWith("tcp://")) {
            return checkTcp(url.substring(6), timeout);
        }
        return checkHttp(url, parameters, timeout);
    }

    private ToolResult checkHttp(String url, Map<String, Object> params, int timeout) {
        long start = System.currentTimeMillis();
        try {
            OkHttpClient client = httpClient.newBuilder()
                    .connectTimeout(timeout, TimeUnit.SECONDS)
                    .readTimeout(timeout, TimeUnit.SECONDS)
                    .build();

            String method = params.getOrDefault("method", "GET").toString();
            Request request = new Request.Builder()
                    .url(url)
                    .method(method, null)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - start;
                int expectedStatus = params.containsKey("expected_status")
                        ? ((Number) params.get("expected_status")).intValue() : 200;

                Map<String, Object> result = new HashMap<>();
                result.put("url", url);
                result.put("status_code", response.code());
                result.put("response_time_ms", duration);
                result.put("healthy", response.code() == expectedStatus);
                result.put("content_length", response.body() != null ? response.body().contentLength() : 0);

                String bodyPreview = response.body() != null ? response.body().string() : "";
                if (bodyPreview.length() > 500) bodyPreview = bodyPreview.substring(0, 500) + "...";
                result.put("body_preview", bodyPreview);

                boolean healthy = response.code() == expectedStatus;
                String status = healthy ? "HEALTHY" : "UNHEALTHY";

                return ToolResult.text(String.format(
                        "Health Check Result: %s\nURL: %s\nStatus Code: %d (expected: %d)\nResponse Time: %dms\nBody: %s",
                        status, url, response.code(), expectedStatus, duration, bodyPreview));
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolResult.text(String.format(
                    "Health Check Result: UNHEALTHY\nURL: %s\nError: %s\nResponse Time: %dms",
                    url, e.getMessage(), duration));
        }
    }

    private ToolResult checkTcp(String hostPort, int timeout) {
        String[] parts = hostPort.split(":");
        if (parts.length != 2) {
            return ToolResult.error("Invalid TCP address. Use format: tcp://host:port");
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout * 1000);
            long duration = System.currentTimeMillis() - start;
            return ToolResult.text(String.format(
                    "TCP Health Check: HEALTHY\nHost: %s\nPort: %d\nResponse Time: %dms",
                    host, port, duration));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolResult.text(String.format(
                    "TCP Health Check: UNHEALTHY\nHost: %s\nPort: %d\nError: %s\nResponse Time: %dms",
                    host, port, e.getMessage(), duration));
        }
    }
}
