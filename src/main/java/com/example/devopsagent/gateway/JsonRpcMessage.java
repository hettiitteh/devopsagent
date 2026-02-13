package com.example.devopsagent.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-RPC 2.0 message format for the Gateway Protocol.
 * Like OpenClaw, all communication uses JSON-RPC over WebSocket.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcMessage {

    private String jsonrpc = "2.0";
    private String id;
    private String method;
    private Object params;
    private Object result;
    private JsonRpcError error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;
    }

    public static JsonRpcMessage request(String id, String method, Object params) {
        return JsonRpcMessage.builder()
                .jsonrpc("2.0")
                .id(id)
                .method(method)
                .params(params)
                .build();
    }

    public static JsonRpcMessage success(String id, Object result) {
        return JsonRpcMessage.builder()
                .jsonrpc("2.0")
                .id(id)
                .result(result)
                .build();
    }

    public static JsonRpcMessage error(String id, int code, String message) {
        return JsonRpcMessage.builder()
                .jsonrpc("2.0")
                .id(id)
                .error(JsonRpcError.builder().code(code).message(message).build())
                .build();
    }

    public static JsonRpcMessage notification(String method, Object params) {
        return JsonRpcMessage.builder()
                .jsonrpc("2.0")
                .method(method)
                .params(params)
                .build();
    }
}
