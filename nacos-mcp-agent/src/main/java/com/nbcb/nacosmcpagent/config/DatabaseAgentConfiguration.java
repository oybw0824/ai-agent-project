package com.nbcb.nacosmcpagent.config;

import com.nbcb.nacosmcpagent.audit.AgentAuditProperties;
import com.nbcb.nacosmcpagent.audit.AgentCallAuditService;
import com.nbcb.nacosmcpagent.audit.McpToolCallLogService;
import com.nbcb.nacosmcpagent.agent.AgentRuntimeRegistry;
import com.nbcb.nacosmcpagent.agent.AgentSkillRegistryFactory;
import com.nbcb.nacosmcpagent.agent.ConfiguredToolResolver;
import com.nbcb.nacosmcpagent.agent.DatabaseChatModelFactory;
import com.nbcb.nacosmcpagent.agent.PromptTemplateLoader;
import com.nbcb.nacosmcpagent.repository.AgentDefinitionRepository;
import com.nbcb.nacosmcpagent.tool.LocalMcpTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 数据库驱动 Agent 的集中 Spring Boot 装配配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentAuditProperties.class)
public class DatabaseAgentConfiguration {

    @Bean
    @ConditionalOnMissingBean(DatabaseChatModelFactory.class)
    public DatabaseChatModelFactory databaseChatModelFactory(
            AgentCallAuditService auditService) {
        return new DatabaseChatModelFactory(auditService);
    }

    @Bean
    @ConditionalOnMissingBean(PromptTemplateLoader.class)
    public PromptTemplateLoader promptTemplateLoader(
            ResourceLoader resourceLoader,
            Environment environment) {
        return new PromptTemplateLoader(resourceLoader, environment);
    }

    @Bean
    @ConditionalOnMissingBean(AgentSkillRegistryFactory.class)
    public AgentSkillRegistryFactory agentSkillRegistryFactory(
            ResourceLoader resourceLoader,
            @Value("${agent.runtime.skills-classpath:skills}")
            String skillsClasspath) {
        return new AgentSkillRegistryFactory(
                resourceLoader,
                skillsClasspath);
    }

    @Bean
    @ConditionalOnMissingBean(ConfiguredToolResolver.class)
    public ConfiguredToolResolver configuredToolResolver(
            @Qualifier("localToolCallbacks")
            List<ToolCallback> localToolCallbacks,
            @Qualifier("distributedAsyncToolCallback")
            ObjectProvider<ToolCallbackProvider> remoteToolProvider,
            @Value("${spring.ai.mcp.server.name:nacos-mcp-agent}")
            String localMcpCode,
            AgentCallAuditService auditService) {
        return new ConfiguredToolResolver(
                localToolCallbacks,
                remoteToolProvider,
                localMcpCode,
                auditService);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntimeRegistry.class)
    @ConditionalOnProperty(
            prefix = "agent.runtime",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentRuntimeRegistry agentRuntimeRegistry(
            AgentDefinitionRepository definitionRepository,
            DatabaseChatModelFactory chatModelFactory,
            ConfiguredToolResolver toolResolver,
            AgentSkillRegistryFactory skillRegistryFactory,
            PromptTemplateLoader promptLoader) {
        return new AgentRuntimeRegistry(
                definitionRepository,
                chatModelFactory,
                toolResolver,
                skillRegistryFactory,
                promptLoader);
    }

    /**
     * 本地工具回调统一构建一次，供 MCP Server 和 Agent 运行时复用。
     */
    @Bean("localToolCallbacks")
    @ConditionalOnMissingBean(name = "localToolCallbacks")
    public List<ToolCallback> localToolCallbacks(
            GenericApplicationContext applicationContext) {
        Map<String, Object> localToolBeans =
                applicationContext.getBeansWithAnnotation(
                        LocalMcpTool.class);
        List<Object> toolObjects = localToolBeans.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        if (toolObjects.isEmpty()) {
            return List.of();
        }
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks();
        return List.copyOf(Arrays.asList(callbacks));
    }

    /**
     * MCP Server 只导出本地 Java 工具，避免再次代理远程工具。
     */
    @Bean("localMcpTools")
    @ConditionalOnMissingBean(name = "localMcpTools")
    public List<McpServerFeatures.AsyncToolSpecification> localMcpTools(
            @Qualifier("localToolCallbacks")
            List<ToolCallback> localToolCallbacks,
            McpToolCallLogService mcpToolCallLogService,
            @Value("${spring.ai.mcp.server.name:nacos-mcp-agent}")
            String mcpServerName,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
            String mcpEndpoint) {
        if (localToolCallbacks.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> loggedCallbacks = localToolCallbacks.stream()
                .map(callback -> mcpToolCallLogService.wrapTool(
                        callback,
                        mcpServerName,
                        mcpEndpoint))
                .toList();
        return McpToolUtils.toAsyncToolSpecifications(
                loggedCallbacks);
    }

    /**
     * Agent 显式管理工具，避免 Spring AI 全局解析器提前访问远程 MCP。
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallbackResolver.class)
    public ToolCallbackResolver toolCallbackResolver(
            GenericApplicationContext applicationContext) {
        return SpringBeanToolCallbackResolver.builder()
                .applicationContext(applicationContext)
                .build();
    }
}
