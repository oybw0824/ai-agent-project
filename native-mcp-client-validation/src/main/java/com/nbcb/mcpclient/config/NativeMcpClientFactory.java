package com.nbcb.mcpclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * 原生 MCP Client 工厂，负责创建连接并完成严格版本协商。
 */
@Slf4j
@Component
public class NativeMcpClientFactory {

    public static final String PROTOCOL_VERSION = "2025-11-25";

    private final ObjectMapper objectMapper;

    public NativeMcpClientFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 创建并初始化 MCP Client。初始化失败时不会返回半初始化客户端。
     */
    public McpAsyncClient create(McpGatewayProperties properties) {
        if (properties.authEnabled()) {
            Assert.hasText(properties.clientToken(), "启用网关鉴权时 AI_GATEWAY_CLIENT_TOKEN 不能为空");
        }

        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl(properties.baseUrl());
        if (properties.authEnabled()
                && StringUtils.hasText(properties.clientToken())) {
            webClientBuilder.defaultHeader(
                    "x-client-token", properties.clientToken());
        }

        WebClientStreamableHttpTransport transport = WebClientStreamableHttpTransport
                .builder(webClientBuilder)
                .endpoint(properties.endpoint())
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .supportedProtocolVersions(List.of(PROTOCOL_VERSION))
                .resumableStreams(true)
                .openConnectionOnStartup(false)
                .build();

        McpAsyncClient client = McpClient.async(transport)
                .clientInfo(new McpSchema.Implementation(
                        "native-mcp-client-validation", "1.0.2"))
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.initializationTimeout())
                .build();

        try {
            McpSchema.InitializeResult result = client.initialize()
                    .block(properties.initializationTimeout());
            Assert.notNull(result, "MCP 初始化结果不能为空");
            if (!PROTOCOL_VERSION.equals(result.protocolVersion())) {
                throw new IllegalStateException("AI 网关协议版本不匹配，期望 "
                        + PROTOCOL_VERSION + "，实际 " + result.protocolVersion());
            }
            log.info("原生 MCP Client 初始化成功：{}{}，协议版本：{}",
                    properties.baseUrl(), properties.endpoint(), result.protocolVersion());
            return client;
        }
        catch (RuntimeException ex) {
            client.close();
            throw new IllegalStateException("原生 MCP Client 初始化失败：" + ex.getMessage(), ex);
        }
    }
}
