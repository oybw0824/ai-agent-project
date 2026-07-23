package com.nbcb.mcpclient.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.Headers;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 2025-11-25 Streamable HTTP 协议验证。
 */
class NativeMcpClientProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldCompleteLifecycleAndCarryRequiredHeaders() {
        GatewayDispatcher dispatcher = new GatewayDispatcher(objectMapper, Mode.SUCCESS);
        server.setDispatcher(dispatcher);

        McpAsyncClient client = createClient(Duration.ofSeconds(2));
        try {
            McpSchema.ListToolsResult tools = client.listTools().block(Duration.ofSeconds(2));
            McpSchema.CallToolResult result = client.callTool(
                            new McpSchema.CallToolRequest(
                                    "mcp-service___calculate",
                                    Map.of("expression", "2+3")))
                    .block(Duration.ofSeconds(2));

            assertThat(tools).isNotNull();
            assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                    .containsExactly("mcp-service___calculate");
            assertThat(result).isNotNull();
            assertThat(result.isError()).isFalse();

            List<CapturedRequest> requests = dispatcher.requests();
            assertThat(requests).extracting(CapturedRequest::method)
                    .containsSubsequence(
                            "initialize",
                            "notifications/initialized",
                            "tools/list",
                            "tools/call");
            assertThat(requests).allSatisfy(request ->
                    assertThat(request.path()).isEqualTo("/mcp"));

            CapturedRequest initialize = requestByMethod(requests, "initialize");
            assertThat(initialize.protocolVersion()).isEqualTo("2025-11-25");
            assertThat(initialize.headers().get("x-client-token"))
                    .isEqualTo("test-jwt");
            assertThat(initialize.headers().get("Accept"))
                    .contains("application/json", "text/event-stream");

            for (CapturedRequest request : requests) {
                assertThat(request.headers().get("x-client-token"))
                        .isEqualTo("test-jwt");
                if (!"initialize".equals(request.method())) {
                    assertThat(request.headers().get("MCP-Protocol-Version"))
                            .isEqualTo("2025-11-25");
                    assertThat(request.headers().get("Mcp-Session-Id"))
                            .isEqualTo("session-001");
                }
            }
        }
        finally {
            client.close();
        }
    }

    @Test
    void shouldFailWhenTokenIsMissing() {
        NativeMcpClientFactory factory = new NativeMcpClientFactory(objectMapper);
        McpGatewayProperties properties = properties(
                true, "", Duration.ofSeconds(1));

        assertThatThrownBy(() -> factory.create(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI_GATEWAY_CLIENT_TOKEN");
    }

    @Test
    void shouldAllowMissingTokenWhenAuthenticationIsDisabled() {
        GatewayDispatcher dispatcher = new GatewayDispatcher(objectMapper, Mode.SUCCESS);
        server.setDispatcher(dispatcher);

        McpAsyncClient client = new NativeMcpClientFactory(objectMapper)
                .create(properties(false, "", Duration.ofSeconds(1)));
        try {
            assertThat(dispatcher.requests())
                    .allSatisfy(request -> assertThat(
                            request.headers().get("x-client-token")).isNull());
        }
        finally {
            client.close();
        }
    }

    @Test
    void shouldFailOnUnauthorizedResponse() {
        server.setDispatcher(new GatewayDispatcher(objectMapper, Mode.UNAUTHORIZED));

        assertThatThrownBy(() -> createClient(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始化失败");
    }

    @Test
    void shouldRejectProtocolDowngrade() {
        server.setDispatcher(new GatewayDispatcher(objectMapper, Mode.VERSION_MISMATCH));

        assertThatThrownBy(() -> createClient(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始化失败");
    }

    @Test
    void shouldFailOnInitializationTimeout() {
        server.setDispatcher(new GatewayDispatcher(objectMapper, Mode.TIMEOUT));

        assertThatThrownBy(() -> createClient(Duration.ofMillis(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始化失败");
    }

    @Test
    void shouldSurfaceExpiredSession() {
        GatewayDispatcher dispatcher = new GatewayDispatcher(objectMapper, Mode.SESSION_EXPIRED);
        server.setDispatcher(dispatcher);
        McpAsyncClient client = createClient(Duration.ofSeconds(1));

        try {
            assertThatThrownBy(() -> client.listTools().block(Duration.ofSeconds(1)))
                    .isInstanceOf(RuntimeException.class);
        }
        finally {
            client.close();
        }
    }

    private McpAsyncClient createClient(Duration timeout) {
        return new NativeMcpClientFactory(objectMapper)
                .create(properties(true, "test-jwt", timeout));
    }

    private McpGatewayProperties properties(
            boolean authEnabled,
            String token,
            Duration timeout) {
        String baseUrl = server.url("/").toString();
        return new McpGatewayProperties(
                baseUrl.substring(0, baseUrl.length() - 1),
                "/mcp",
                "mcp-service",
                authEnabled,
                token,
                timeout,
                timeout);
    }

    private CapturedRequest requestByMethod(
            List<CapturedRequest> requests,
            String method) {
        return requests.stream()
                .filter(request -> method.equals(request.method()))
                .findFirst()
                .orElseThrow();
    }

    private enum Mode {
        SUCCESS,
        UNAUTHORIZED,
        VERSION_MISMATCH,
        TIMEOUT,
        SESSION_EXPIRED
    }

    private record CapturedRequest(
            String method,
            String protocolVersion,
            Headers headers,
            String body,
            String path) {
    }

    private static final class GatewayDispatcher extends Dispatcher {

        private final ObjectMapper objectMapper;
        private final Mode mode;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private GatewayDispatcher(ObjectMapper objectMapper, Mode mode) {
            this.objectMapper = objectMapper;
            this.mode = mode;
        }

        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest request) {
            if ("GET".equals(request.getMethod())) {
                return new MockResponse().setResponseCode(405);
            }
            if ("DELETE".equals(request.getMethod())) {
                return new MockResponse().setResponseCode(204);
            }
            if (mode == Mode.UNAUTHORIZED) {
                return new MockResponse().setResponseCode(401);
            }

            String body = request.getBody().readUtf8();
            try {
                JsonNode json = objectMapper.readTree(body);
                String method = json.path("method").asText();
                String protocolVersion = json.path("params")
                        .path("protocolVersion").asText(null);
                requests.add(new CapturedRequest(
                        method, protocolVersion, request.getHeaders(), body,
                        request.getPath()));

                if (mode == Mode.TIMEOUT && "initialize".equals(method)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(initializeResponse(json.path("id"), "2025-11-25"))
                            .setBodyDelay(2, TimeUnit.SECONDS);
                }
                if ("initialize".equals(method)) {
                    String version = mode == Mode.VERSION_MISMATCH
                            ? "2025-06-18"
                            : "2025-11-25";
                    return jsonResponse(initializeResponse(json.path("id"), version))
                            .setHeader("Mcp-Session-Id", "session-001");
                }
                if ("notifications/initialized".equals(method)) {
                    return new MockResponse().setResponseCode(202);
                }
                if (mode == Mode.SESSION_EXPIRED) {
                    return new MockResponse().setResponseCode(404);
                }
                if ("tools/list".equals(method)) {
                    return jsonResponse("""
                            {"jsonrpc":"2.0","id":%s,"result":{"tools":[{
                              "name":"mcp-service___calculate",
                              "description":"计算数学表达式",
                              "inputSchema":{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}
                            }]}}
                            """.formatted(json.path("id")));
                }
                if ("tools/call".equals(method)) {
                    return jsonResponse("""
                            {"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"5"}],"isError":false}}
                            """.formatted(json.path("id")));
                }
                return new MockResponse().setResponseCode(400);
            }
            catch (Exception ex) {
                return new MockResponse().setResponseCode(500).setBody(ex.getMessage());
            }
        }

        private MockResponse jsonResponse(String body) {
            return new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body);
        }

        private String initializeResponse(JsonNode id, String version) {
            return """
                    {"jsonrpc":"2.0","id":%s,"result":{
                      "protocolVersion":"%s",
                      "capabilities":{"tools":{"listChanged":false}},
                      "serverInfo":{"name":"mock-ai-gateway","version":"1.0.0"}
                    }}
                    """.formatted(id, version);
        }

        private List<CapturedRequest> requests() {
            return new ArrayList<>(requests);
        }
    }
}
