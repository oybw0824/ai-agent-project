package com.example.aigateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(GatewayMcpProperties.class)
@ConditionalOnProperty(prefix = GatewayMcpProperties.PREFIX, name = "enabled", havingValue = "true")
public class GatewayMcpAutoConfiguration {

    @Bean
    DefaultGatewayMcpClientTokenUpdater gatewayMcpClientTokenUpdater(GatewayMcpProperties properties) {
        return new DefaultGatewayMcpClientTokenUpdater(properties.clientToken());
    }

    @Bean
    @ConditionalOnMissingBean
    GatewayMcpClient gatewayMcpClient(GatewayMcpProperties properties, ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistry, DefaultGatewayMcpClientTokenUpdater tokenUpdater) {
        return new GatewayMcpClient(properties, objectMapper, meterRegistry.getIfAvailable(), tokenUpdater);
    }

    @Bean("gatewayMcpToolCallbacks")
    @ConditionalOnMissingBean(name = "gatewayMcpToolCallbacks")
    GatewayMcpToolCallbackProvider gatewayMcpToolCallbackProvider(
            GatewayMcpProperties properties, GatewayMcpClient client) {
        return new GatewayMcpToolCallbackProvider(properties, client);
    }
}
