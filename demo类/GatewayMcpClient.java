package com.example.aigateway.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class GatewayMcpClient {

    static final String SESSION_HEADER = "MCP-Session-Id";
    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final String CLIENT_NAME = "nebula-ai-gateway-mcp-sdk";
    private static final String CLIENT_VERSION = "1.0.0";

    private final GatewayMcpProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final DefaultGatewayMcpClientTokenUpdater tokenUpdater;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicLong requestIds = new AtomicLong();


    GatewayMcpClient(GatewayMcpProperties properties, ObjectMapper objectMapper,
            MeterRegistry meterRegistry, DefaultGatewayMcpClientTokenUpdater tokenUpdater) {
        this(properties, objectMapper, createWebClient(properties), meterRegistry, tokenUpdater);
    }
    GatewayMcpClient(GatewayMcpProperties properties, ObjectMapper objectMapper, WebClient webClient,
            MeterRegistry meterRegistry, DefaultGatewayMcpClientTokenUpdater tokenUpdater) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.meterRegistry = meterRegistry;
        this.tokenUpdater = tokenUpdater;
    }

    private static WebClient createWebClient(GatewayMcpProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.responseTimeout());
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE);
        return builder.build();
    }

    public Mono<Void> initialize() {
        properties.validateUniqueApplications();
        Map<String, Object> request = rpc(nextId("initialize"), "initialize", Map.of(
                "protocolVersion", properties.protocolVersion(),
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION)));
        return webClient.post().uri("/mcp").headers(tokenUpdater::applyTo).bodyValue(request).exchangeToMono(response ->
                parseResponse(response).flatMap(body -> {
                    throwIfRpcError(body);
                    String returnedSessionId = response.headers().header(SESSION_HEADER).stream().findFirst()
                            .orElseThrow(() -> new GatewayMcpException("initialize response is missing " + SESSION_HEADER));
                    sessionId.set(returnedSessionId);
                    return Mono.empty();
                }))
                .then(Mono.defer(this::sendInitializedNotification))
                .onErrorMap(this::asGatewayException);
    }

    private Mono<Void> sendInitializedNotification() {
        return webClient.post().uri("/mcp")
                .headers(this::addSessionHeaders)
                .bodyValue(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()))
                .exchangeToMono(response -> response.releaseBody().then(checkStatus(response)))
                .onErrorMap(this::asGatewayException);
    }

    public Mono<List<GatewayMcpTool>> listTools(String applicationName) {
        return webClient.post().uri("/mcp/{application}", applicationName)
                .headers(this::addSessionHeaders)
                .bodyValue(rpc(nextId("tools-list"), "tools/list", Map.of()))
                .exchangeToMono(this::parseResponse)
                .map(body -> {
                    throwIfRpcError(body);
                    JsonNode tools = body.path("result").path("tools");
                    if (!tools.isArray()) {
                        throw new GatewayMcpException("tools/list response does not contain result.tools array");
                    }
                    List<GatewayMcpTool> result = new ArrayList<>();
                    tools.forEach(tool -> result.add(new GatewayMcpTool(
                            requiredText(tool, "name"),
                            tool.path("description").asText(""),
                            tool.path("inputSchema").isMissingNode() ? objectMapper.createObjectNode() : tool.path("inputSchema"))));
                    return List.copyOf(result);
                })
                .onErrorMap(this::asGatewayException);
    }

    public Mono<String> callTool(String applicationName, String toolName, String argumentsJson) {
        return Mono.defer(() -> {
            Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
            return doCallTool(applicationName, toolName, argumentsJson)
                    .doOnSuccess(result -> recordToolCall(sample, applicationName, toolName, "SUCCESS", "none"))
                    .doOnError(error -> recordToolCall(sample, applicationName, toolName, "ERROR",
                            error.getClass().getSimpleName()));
        });
    }

    private Mono<String> doCallTool(String applicationName, String toolName, String argumentsJson) {
        final JsonNode arguments;
        try {
            arguments = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        }
        catch (JsonProcessingException ex) {
            return Mono.error(new GatewayMcpException("Tool arguments are not valid JSON", ex));
        }
        if (!arguments.isObject()) {
            return Mono.error(new GatewayMcpException("Tool arguments must be a JSON object"));
        }

        Map<String, Object> params = Map.of("name", toolName, "arguments", arguments);
        // Intentionally no retry operator: tools/call may wrap non-idempotent REST operations.
        return webClient.post().uri("/mcp")
                .headers(this::addSessionHeaders)
                .bodyValue(rpc(nextId("tool-call"), "tools/call", params))
                .exchangeToMono(this::parseResponse)
                .map(body -> readableToolResult(applicationName, toolName, body))
                .onErrorMap(this::asGatewayException);
    }

    private void recordToolCall(Timer.Sample sample, String applicationName, String toolName,
            String outcome, String exception) {
        if (sample == null) {
            return;
        }
        sample.stop(Timer.builder("nebula.ai.gateway.mcp.tool.calls")
                .description("AI gateway MCP tools/call requests")
                .tag("application", applicationName)
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .tag("exception", exception)
                .register(meterRegistry));
    }

    public Mono<Void> close() {
        if (sessionId.get() == null) {
            return Mono.empty();
        }
        return webClient.delete().uri("/mcp").headers(this::addSessionHeaders)
                .exchangeToMono(response -> response.releaseBody().then(
                        response.statusCode().value() == 405 ? Mono.empty() : checkStatus(response)))
                .doFinally(signal -> sessionId.set(null))
                .onErrorMap(this::asGatewayException);
    }

    private String readableToolResult(String applicationName, String toolName, JsonNode body) {
        throwIfRpcError(body);
        JsonNode result = body.path("result");
        List<String> parts = new ArrayList<>();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            content.forEach(item -> parts.add(item.hasNonNull("text") ? item.get("text").asText() : compact(item)));
        }
        if (result.hasNonNull("structuredContent")) {
            parts.add(compact(result.get("structuredContent")));
        }
        String readable = parts.isEmpty() ? compact(result) : String.join("\n", parts);
        if (result.path("isError").asBoolean(false)) {
            throw new GatewayMcpException("MCP tool " + applicationName + "/" + toolName + " failed: " + readable);
        }
        return readable;
    }

    private Mono<JsonNode> parseResponse(ClientResponse response) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMap(body -> Mono.error(new GatewayMcpException(
                            "AI gateway HTTP " + response.statusCode().value() + ": " + body)));
        }
        return response.bodyToMono(String.class).defaultIfEmpty("").map(this::parseJsonOrSse);
    }

    private JsonNode parseJsonOrSse(String body) {
        String payload = body.strip();
        if (payload.startsWith("data:")) {
            payload = payload.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip()).filter(line -> !line.equals("[DONE]"))
                    .findFirst().orElseThrow(() -> new GatewayMcpException("SSE response has no JSON data event"));
        }
        try {
            return objectMapper.readTree(payload);
        }
        catch (JsonProcessingException ex) {
            throw new GatewayMcpException("AI gateway returned invalid JSON", ex);
        }
    }

    private Mono<Void> checkStatus(ClientResponse response) {
        return response.statusCode().isError()
                ? Mono.error(new GatewayMcpException("AI gateway HTTP " + response.statusCode().value()))
                : Mono.empty();
    }

    private void addSessionHeaders(HttpHeaders headers) {
        tokenUpdater.applyTo(headers);
        String current = sessionId.get();
        if (current == null) {
            throw new GatewayMcpException("MCP session is not initialized");
        }
        headers.set(SESSION_HEADER, current);
        headers.set(PROTOCOL_HEADER, properties.protocolVersion());
    }

    private static Map<String, Object> rpc(String id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private String nextId(String prefix) {
        return prefix + "-" + requestIds.incrementAndGet();
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new GatewayMcpException("Missing required field: " + field);
        }
        return node.get(field).asText();
    }

    private static void throwIfRpcError(JsonNode response) {
        JsonNode error = response.path("error");
        if (!error.isMissingNode()) {
            throw new GatewayMcpException("MCP JSON-RPC error " + error.path("code").asText("unknown")
                    + ": " + error.path("message").asText("unknown error")
                    + (error.has("data") ? "; data=" + error.get("data") : ""));
        }
    }

    private String compact(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        }
        catch (JsonProcessingException ex) {
            return node.toString();
        }
    }

    private Throwable asGatewayException(Throwable error) {
        return error instanceof GatewayMcpException ? error : new GatewayMcpException("AI gateway MCP call failed", error);
    }
}
