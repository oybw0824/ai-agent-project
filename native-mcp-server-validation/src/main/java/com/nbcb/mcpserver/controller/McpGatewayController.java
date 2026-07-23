package com.nbcb.mcpserver.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.mcpserver.tool.ValidationTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按接入文档提供 Streamable HTTP MCP 接口的本地 AI 网关。
 *
 * <p>同时支持全量端点 {@code /mcp} 和指定应用端点
 * {@code /mcp/{appName}}。为了验证方便，接收但不校验
 * {@code x-client-token}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
public class McpGatewayController {

    public static final String PROTOCOL_VERSION = "2025-11-25";
    public static final String APP_NAME = "mcp-service";
    public static final String SESSION_HEADER = "MCP-Session-Id";
    public static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    private final ValidationTools validationTools;
    private final ObjectMapper objectMapper;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public McpGatewayController(
            ValidationTools validationTools,
            ObjectMapper objectMapper) {
        this.validationTools = validationTools;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理全量应用和指定应用的 JSON-RPC 请求。
     */
    @PostMapping(
            value = {"", "/{appName}"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(
            @PathVariable(required = false) String appName,
            @RequestBody JsonRpcRequest request,
            @RequestHeader(value = "x-client-token", required = false)
            String ignoredClientToken,
            @RequestHeader(value = SESSION_HEADER, required = false)
            String sessionId) {
        log.info("AI 网关收到请求：appName={}, method={}, tokenPresent={}",
                appName, request.method(), ignoredClientToken != null);

        return switch (request.method()) {
            case "initialize" -> initialize(request);
            case "notifications/initialized" -> initialized(sessionId);
            case "tools/list" -> jsonResponse(
                    success(request.id(), Map.of("tools", listTools(appName))),
                    sessionId);
            case "tools/call" -> callTool(appName, request, sessionId);
            case "ping" -> jsonResponse(success(request.id(), Map.of()), sessionId);
            default -> jsonResponse(error(
                    request.id(), -32601,
                    "Method not found: " + request.method()), sessionId);
        };
    }

    /**
     * 关闭客户端会话。
     */
    @DeleteMapping({"", "/{appName}"})
    public ResponseEntity<Void> closeSession(
            @RequestHeader(value = SESSION_HEADER, required = false)
            String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, Object>> initialize(
            JsonRpcRequest request) {
        String requestedVersion = request.params() == null
                ? null
                : request.params().path("protocolVersion").asText(null);
        if (!PROTOCOL_VERSION.equals(requestedVersion)) {
            return jsonResponse(error(
                    request.id(), -32602,
                    "Unsupported protocol version: " + requestedVersion), null);
        }

        String sessionId = UUID.randomUUID().toString();
        sessions.add(sessionId);
        Map<String, Object> result = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false)),
                "serverInfo", Map.of(
                        "name", "native-ai-gateway-validation",
                        "version", "1.0.2"),
                "instructions", "本地 AI 网关验证服务，不校验 JWT");
        return jsonResponse(success(request.id(), result), sessionId);
    }

    private ResponseEntity<Void> initialized(String sessionId) {
        if (sessionId != null && !sessions.contains(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.accepted()
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .build();
    }

    private List<Map<String, Object>> listTools(String appName) {
        if (appName != null && !APP_NAME.equals(appName)) {
            return List.of();
        }
        String prefix = appName == null ? APP_NAME + "___" : "";
        return List.of(
                tool(prefix + "calculate",
                        "计算数学表达式，支持加减乘除、小数和括号",
                        "expression", "数学表达式，例如 (12+8)*3"),
                tool(prefix + "getWeatherByCity",
                        "查询中国城市的模拟天气",
                        "city", "城市名称，例如北京、上海、深圳"));
    }

    private ResponseEntity<Map<String, Object>> callTool(
            String appName,
            JsonRpcRequest request,
            String sessionId) {
        if (request.params() == null) {
            return jsonResponse(error(
                    request.id(), -32602, "params is required"), sessionId);
        }
        String requestedName = request.params().path("name").asText("");
        String toolName = resolveToolName(appName, requestedName);
        if (toolName == null) {
            return jsonResponse(error(
                    request.id(), -32602,
                    "Tool not found: " + requestedName), sessionId);
        }

        JsonNode arguments = request.params().path("arguments");
        try {
            Object result = switch (toolName) {
                case "calculate" -> validationTools.calculate(
                        requiredText(arguments, "expression"));
                case "getWeatherByCity" -> validationTools.getWeatherByCity(
                        requiredText(arguments, "city"));
                default -> throw new IllegalArgumentException(
                        "Tool not found: " + requestedName);
            };
            return jsonResponse(success(request.id(), toolResult(
                    objectMapper.writeValueAsString(result), false)), sessionId);
        }
        catch (IllegalArgumentException ex) {
            return jsonResponse(success(request.id(), toolResult(
                    ex.getMessage(), true)), sessionId);
        }
        catch (JsonProcessingException ex) {
            return jsonResponse(error(
                    request.id(), -32603, "Tool result serialization failed"),
                    sessionId);
        }
    }

    private String resolveToolName(String appName, String requestedName) {
        if (appName == null) {
            String prefix = APP_NAME + "___";
            return requestedName.startsWith(prefix)
                    ? knownTool(requestedName.substring(prefix.length()))
                    : null;
        }
        if (!APP_NAME.equals(appName)) {
            return null;
        }
        return knownTool(requestedName);
    }

    private String knownTool(String name) {
        return "calculate".equals(name) || "getWeatherByCity".equals(name)
                ? name
                : null;
    }

    private String requiredText(JsonNode arguments, String name) {
        String value = arguments.path(name).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required argument: " + name);
        }
        return value;
    }

    private Map<String, Object> tool(
            String name,
            String description,
            String argumentName,
            String argumentDescription) {
        Map<String, Object> property = Map.of(
                "type", "string",
                "description", argumentDescription);
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(argumentName, property),
                "required", List.of(argumentName),
                "additionalProperties", false);
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", schema);
    }

    private Map<String, Object> toolResult(String text, boolean isError) {
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", text)),
                "isError", isError);
    }

    private Map<String, Object> success(JsonNode id, Object result) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result);
    }

    private Map<String, Object> error(
            JsonNode id,
            int code,
            String message) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "error", Map.of(
                        "code", code,
                        "message", message));
    }

    private <T> ResponseEntity<T> jsonResponse(T body, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(PROTOCOL_HEADER, PROTOCOL_VERSION);
        if (sessionId != null) {
            headers.set(SESSION_HEADER, sessionId);
        }
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /**
     * JSON-RPC 2.0 请求。
     */
    public record JsonRpcRequest(
            String jsonrpc,
            JsonNode id,
            String method,
            JsonNode params) {
    }
}
