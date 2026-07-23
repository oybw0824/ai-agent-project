package com.nbcb.mcpserver.controller;

import com.nbcb.mcpserver.tool.ValidationTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档约定的 AI 网关 MCP 接口测试。
 */
@WebMvcTest(McpGatewayController.class)
@Import(ValidationTools.class)
class McpGatewayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("完成初始化并返回协议版本和会话")
    void shouldInitializeWithSession() throws Exception {
        mockMvc.perform(post("/mcp/mcp-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON,
                                MediaType.TEXT_EVENT_STREAM)
                        .content(initializeRequest()))
                .andExpect(status().isOk())
                .andExpect(header().exists(
                        McpGatewayController.SESSION_HEADER))
                .andExpect(header().string(
                        McpGatewayController.PROTOCOL_HEADER,
                        McpGatewayController.PROTOCOL_VERSION))
                .andExpect(jsonPath("$.result.protocolVersion")
                        .value(McpGatewayController.PROTOCOL_VERSION));
    }

    @Test
    @DisplayName("指定应用查询返回无前缀工具且不校验 Token")
    void shouldListApplicationToolsWithoutTokenValidation() throws Exception {
        mockMvc.perform(post("/mcp/mcp-service")
                        .header("x-client-token", "any-invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"list-1",\
                                "method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(2))
                .andExpect(jsonPath("$.result.tools[0].name")
                        .value("calculate"))
                .andExpect(jsonPath("$.result.tools[1].name")
                        .value("getWeatherByCity"));
    }

    @Test
    @DisplayName("全量查询和调用使用应用名前缀")
    void shouldListAndCallPrefixedTool() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"list-all",\
                                "method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name")
                        .value("mcp-service___calculate"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"call-all",\
                                "method":"tools/call","params":{\
                                "name":"mcp-service___calculate",\
                                "arguments":{"expression":"(12+8)*3"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value(startsWith("{\"expression\":")));
    }

    @Test
    @DisplayName("指定应用直接调用天气工具")
    void shouldCallApplicationTool() throws Exception {
        mockMvc.perform(post("/mcp/mcp-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"call-app",\
                                "method":"tools/call","params":{\
                                "name":"getWeatherByCity",\
                                "arguments":{"city":"北京"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value(startsWith("{\"city\":\"北京\"")));
    }

    @Test
    @DisplayName("工具业务异常使用 isError 返回")
    void shouldReturnToolExecutionError() throws Exception {
        mockMvc.perform(post("/mcp/mcp-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"call-error",\
                                "method":"tools/call","params":{\
                                "name":"calculate",\
                                "arguments":{"expression":"1/0"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    @DisplayName("不存在的应用返回空工具列表")
    void shouldReturnEmptyToolsForUnknownApplication() throws Exception {
        mockMvc.perform(post("/mcp/not-found")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"list-empty",\
                                "method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(0));
    }

    @Test
    @DisplayName("支持 initialized 通知和会话关闭")
    void shouldAcceptInitializedAndDeleteSession() throws Exception {
        String sessionId = mockMvc.perform(post("/mcp/mcp-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initializeRequest()))
                .andReturn().getResponse()
                .getHeader(McpGatewayController.SESSION_HEADER);

        mockMvc.perform(post("/mcp/mcp-service")
                        .header(McpGatewayController.SESSION_HEADER, sessionId)
                        .header(McpGatewayController.PROTOCOL_HEADER,
                                McpGatewayController.PROTOCOL_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0",\
                                "method":"notifications/initialized"}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(delete("/mcp/mcp-service")
                        .header(McpGatewayController.SESSION_HEADER, sessionId))
                .andExpect(status().isNoContent());
    }

    private String initializeRequest() {
        return """
                {"jsonrpc":"2.0","id":"init-1","method":"initialize",\
                "params":{"protocolVersion":"2025-11-25",\
                "capabilities":{},"clientInfo":{\
                "name":"test-client","version":"1.0.0"}}}
                """;
    }
}
