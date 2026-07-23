package com.nbcb.mcpclient.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * AI 网关 MCP 连接配置。
 */
@Validated
@ConfigurationProperties(prefix = "ai-gateway")
public record McpGatewayProperties(
        @NotBlank String baseUrl,
        @NotBlank String endpoint,
        @NotBlank String applicationName,
        boolean authEnabled,
        String clientToken,
        @NotNull Duration requestTimeout,
        @NotNull Duration initializationTimeout) {
}
