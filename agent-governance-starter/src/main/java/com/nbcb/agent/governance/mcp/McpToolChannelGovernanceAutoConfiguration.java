package com.nbcb.agent.governance.mcp;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.WebFilter;

import java.util.Map;

/**
 * MCP Server 工具渠道治理自动装配。
 */
@AutoConfiguration
@AutoConfigureAfter(MybatisPlusAutoConfiguration.class)
@AutoConfigureBefore(name = "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebFluxAutoConfiguration")
@ConditionalOnClass(name = {
        "io.modelcontextprotocol.server.McpServerFeatures",
        "io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "agent-governance", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpToolChannelGovernanceProperties.class)
@MapperScan(basePackageClasses = McpToolChannelBlockMapper.class)
public class McpToolChannelGovernanceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-governance.mcp-tool-channel", name = "enabled", havingValue = "true", matchIfMissing = true)
    public McpToolChannelGovernanceManager mcpToolChannelGovernanceManager(
            McpToolChannelBlockMapper mapper,
            McpToolChannelGovernanceProperties properties,
            @Value("${spring.ai.mcp.server.name:}")
            String mcpServerName) {
        return new McpToolChannelGovernanceManager(
                mapper,
                properties,
                mcpServerName);
    }

    @Bean
    @ConditionalOnMissingBean(name = "mcpToolSpecificationGovernancePostProcessor")
    @ConditionalOnProperty(prefix = "agent-governance.mcp-tool-channel", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static BeanPostProcessor mcpToolSpecificationGovernancePostProcessor(
            McpToolChannelGovernanceManager governanceManager) {
        return new McpToolSpecificationGovernancePostProcessor(
                governanceManager);
    }

    @Bean
    @ConditionalOnClass(WebFilter.class)
    @ConditionalOnMissingBean(name = "mcpToolChannelContextWebFilter")
    @ConditionalOnProperty(prefix = "agent-governance.mcp-tool-channel", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WebFilter mcpToolChannelContextWebFilter(
            McpToolChannelGovernanceProperties properties) {
        return new McpToolChannelContextWebFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-governance.mcp-tool-channel", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WebFluxStreamableServerTransportProvider webFluxStreamableServerTransportProvider(
            @Qualifier("mcpServerObjectMapper")
            ObjectMapper objectMapper,
            org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties serverProperties,
            McpToolChannelGovernanceProperties properties) {
        return WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(serverProperties.getMcpEndpoint())
                .keepAliveInterval(serverProperties.getKeepAliveInterval())
                .disallowDelete(serverProperties.isDisallowDelete())
                .contextExtractor(request -> extractChannelContext(request, properties))
                .build();
    }

    private McpTransportContext extractChannelContext(
            ServerRequest request,
            McpToolChannelGovernanceProperties properties) {
        String channelCode = request.headers()
                .firstHeader(properties.getChannelHeader());
        if (!StringUtils.hasText(channelCode)) {
            return McpTransportContext.EMPTY;
        }
        return McpTransportContext.create(Map.of(
                McpToolChannelContext.CHANNEL_CONTEXT_KEY,
                channelCode.trim()));
    }
}
