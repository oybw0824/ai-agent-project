package com.nbcb.agent.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.constant.PromptConstant;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.service.PromptService;
import com.nbcb.agent.skill.dynamic.AgentSkillLocalStore;
import com.nbcb.agent.skill.dynamic.DynamicSkillProperties;
import com.nbcb.agent.skill.dynamic.DynamicSkillsAgentHook;

import com.nbcb.agent.governance.AgentGovernanceProperties;
import com.nbcb.agent.governance.LoopDetectHook;
import com.nbcb.agent.governance.McpToolRegistrar;
import com.nbcb.agent.governance.ModelCallLimitHook;
import com.nbcb.agent.governance.SessionTimeoutBudgetHook;
import com.nbcb.agent.governance.TokenBudgetHook;
import com.nbcb.agent.governance.ToolAvailabilityHook;
import com.nbcb.agent.governance.ToolGovernanceInterceptor;
import com.nbcb.agent.governance.ToolGovernanceProperties;
import com.nbcb.agent.governance.UnifiedToolWrapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 核心配置 — ReactAgent + SkillsAgentHook + MCP 工具 + 治理 Hook
 *
 * @author com.nbcb
 */
@Slf4j
@Configuration
public class AgentConfig {

    private final ObjectMapper objectMapper;

    public AgentConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initStreamEventMapper() {
        StreamEvent.setObjectMapper(objectMapper);
        log.info("★ StreamEvent ObjectMapper 已注入");
    }

    private static final String AGENT_DESCRIPTION = "基于动态 Skill 快照的技能驱动智能助手";
    private static final String OUTPUT_KEY = "agent_result";
    private static final String FALLBACK_SYSTEM_PROMPT = "你是一个技能驱动的智能助手。";

    /**
     * ★ 唯一主 ToolCallbackProvider — 在此处统一拦截所有工具（治理+缓存+SSE）。
     * Agent 拿到的是已包装好的工具，完全无感。
     */
    @Bean
    @Primary
    public ToolCallbackProvider primaryToolCallbackProvider(
            @Autowired(required = false)
            @Qualifier("mcpAsyncToolCallbacks") ToolCallbackProvider higressTools,
            ToolGovernanceInterceptor governance,
            @Value("${agent.tool-cache.max-size:500}") int cacheSize,
            @Value("${agent.tool-cache.ttl-seconds:300}") int cacheTtl,
            @Value("${agent.tool-cache.cacheable-tools:}") String cacheableToolNames) {

        if (higressTools == null) return () -> new ToolCallback[0];

        // 工具结果缓存
        Cache<String, String> toolCache = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterWrite(Duration.ofSeconds(cacheTtl))
                .build();
        Set<String> cacheableTools = Arrays.stream(cacheableToolNames.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        return () -> {
            ToolCallback[] raw = higressTools.getToolCallbacks();
            return Arrays.stream(raw)
                    .map(tool -> new UnifiedToolWrapper(tool, toolCache, governance,
                            cacheableTools.contains(tool.getToolDefinition().name())))
                    .toArray(ToolCallback[]::new);
        };
    }

    @Bean
    public ToolGovernanceInterceptor toolGovernanceInterceptor(ToolGovernanceProperties properties) {
        return new ToolGovernanceInterceptor(properties);
    }

    @Bean
    public McpToolRegistrar mcpToolRegistrar(ToolGovernanceProperties properties,
                                          @Autowired(required = false)
                                          @Qualifier("primaryToolCallbackProvider") ToolCallbackProvider provider) {
        return new McpToolRegistrar(properties, provider);
    }

    @Bean
    @Primary
    public ReactAgent reactAgent(
            ChatModel chatModel,
            PromptService promptService,
            McpToolRegistrar mcpToolRegistrar,
            AgentGovernanceProperties governanceProperties,
            ToolGovernanceProperties toolGovernanceProperties,
            DynamicSkillProperties dynamicSkillProperties,
            AgentSkillLocalStore agentSkillLocalStore) {

        String systemPrompt = promptService.getSystemPrompt(PromptConstant.AGENT_SYSTEM);
        if (systemPrompt == null) systemPrompt = FALLBACK_SYSTEM_PROMPT;

        // ★ 工具已由 primaryToolCallbackProvider 统一包装，这里直接取
        List<ToolCallback> tools = mcpToolRegistrar.loadAvailableTools();

        DynamicSkillsAgentHook skillsHook = new DynamicSkillsAgentHook(
                dynamicSkillProperties.getAgentName(), agentSkillLocalStore);

        List<com.alibaba.cloud.ai.graph.agent.hook.Hook> hooks = new ArrayList<>();
        hooks.add(skillsHook);
        hooks.add(new SessionTimeoutBudgetHook(governanceProperties));
        hooks.add(new ModelCallLimitHook(governanceProperties));
        hooks.add(new TokenBudgetHook(governanceProperties));
        hooks.add(new ToolAvailabilityHook(toolGovernanceProperties));
        hooks.add(new LoopDetectHook(governanceProperties));

        log.info("★ DynamicSkillsAgentHook + {} 治理 Hook + {} 工具",
                hooks.size() - 1, tools.size());

        return ReactAgent.builder()
                .name(dynamicSkillProperties.getAgentName()).description(AGENT_DESCRIPTION)
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .hooks(hooks)
                .tools(tools.toArray(ToolCallback[]::new))
                .outputKey(OUTPUT_KEY)
                .enableLogging(true)
                .build();
    }
}
