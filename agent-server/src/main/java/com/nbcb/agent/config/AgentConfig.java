package com.nbcb.agent.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.constant.PromptConstant;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.PromptService;
import com.nbcb.agent.skill.SkillRegistry;

import com.nbcb.agent.governance.AgentGovernanceProperties;
import com.nbcb.agent.governance.LoopDetectHook;
import com.nbcb.agent.governance.McpToolRegistrar;
import com.nbcb.agent.governance.ModelCallLimitHook;
import com.nbcb.agent.governance.SessionTimeoutBudgetHook;
import com.nbcb.agent.governance.TokenBudgetHook;
import com.nbcb.agent.governance.ToolAvailabilityHook;
import com.nbcb.agent.governance.ToolEventHook;
import com.nbcb.agent.governance.ToolEventInterceptor;
import com.nbcb.agent.governance.ToolGovernanceInterceptor;
import com.nbcb.agent.governance.ToolGovernanceProperties;
import com.nbcb.agent.governance.ToolGovernanceWrapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import com.nbcb.agent.skill.UnprefixedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心配置 — ReactAgent + SkillsAgentHook + MCP 工具 + 治理 Hook
 * <p>
 * 技能通过框架 SkillsAgentHook 注入：LLM 通过 read_skill 工具按需加载技能完整内容。
 *
 * @author com.nbcb
 */
@Slf4j
@Configuration
public class AgentConfig {

    private final ObjectMapper objectMapper;
    private final AgentMetrics metrics;

    public AgentConfig(ObjectMapper objectMapper, AgentMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostConstruct
    public void initStreamEventMapper() {
        StreamEvent.setObjectMapper(objectMapper);
        ToolEventInterceptor.setMetrics(metrics);
        log.info("★ StreamEvent ObjectMapper 已注入，ToolEventInterceptor 监控已注入");
    }

    private static final String AGENT_NAME = "skill-agent";
    private static final String AGENT_DESCRIPTION = "基于 SkillsAgentHook 的技能驱动智能助手";
    private static final String OUTPUT_KEY = "agent_result";
    private static final String FALLBACK_SYSTEM_PROMPT = "你是一个技能驱动的智能助手。";

    /**
     * ★ 唯一主 ToolCallbackProvider — 解决自动配置产生两个 Bean 的冲突
     */
    @Bean
    @Primary
    public ToolCallbackProvider primaryToolCallbackProvider(
            @Autowired(required = false)
            @Qualifier("distributedAsyncToolCallback") ToolCallbackProvider distributedTools) {
        if (distributedTools != null) {
            return new com.nbcb.agent.skill.UnprefixedToolCallbackProvider(distributedTools);
        }
        return () -> new ToolCallback[0];
    }

    @Bean
    public ToolGovernanceInterceptor toolGovernanceInterceptor(ToolGovernanceProperties properties) {
        return new ToolGovernanceInterceptor(properties);
    }

    @Bean
    public McpToolRegistrar mcpToolRegistrar(ToolGovernanceProperties properties,
                                          @Autowired(required = false)
                                          @Qualifier("primaryToolCallbackProvider") ToolCallbackProvider distributedTools) {
        return new McpToolRegistrar(properties, distributedTools);
    }

    /**
     * ★ 单一 ReactAgent — SkillsAgentHook + 治理 Hook + MCP 工具
     */
    @Bean
    @Primary
    public ReactAgent reactAgent(
            ChatModel chatModel,
            SkillRegistry skillRegistry,
            PromptService promptService,
            ToolGovernanceInterceptor governanceInterceptor,
            McpToolRegistrar mcpToolRegistrar,
            AgentGovernanceProperties governanceProperties,
            ToolGovernanceProperties toolGovernanceProperties,
            AgentMetrics metrics) {

        String systemPrompt = promptService.getSystemPrompt(PromptConstant.AGENT_SYSTEM);
        if (systemPrompt == null) systemPrompt = FALLBACK_SYSTEM_PROMPT;

        // ★ 工具：加载 + 治理包装（SSE 事件由 ToolEventInterceptor 统一处理）
        List<ToolCallback> availableTools = mcpToolRegistrar.loadAvailableTools();
        ToolCallback[] wrappedTools = availableTools.stream()
            .map(tool -> new ToolGovernanceWrapper(tool, governanceInterceptor))
            .toArray(ToolCallback[]::new);

        // ★ SkillsAgentHook：提供 read_skill 工具 + 技能列表注入 system prompt
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)  // classpath 技能文件运行时不变，关闭每次请求重新扫描
                .build();

        // ★ 组装 Hook：SkillsAgentHook + 5 个治理 Hook + ToolEventHook（工具事件推送）
        List<com.alibaba.cloud.ai.graph.agent.hook.Hook> allHooks = new ArrayList<>();
        allHooks.add(skillsHook);
        allHooks.add(new SessionTimeoutBudgetHook(governanceProperties, metrics));
        allHooks.add(new ModelCallLimitHook(governanceProperties, metrics));
        allHooks.add(new TokenBudgetHook(governanceProperties, metrics));
        allHooks.add(new ToolAvailabilityHook(toolGovernanceProperties, metrics));
        allHooks.add(new LoopDetectHook(governanceProperties, metrics));
        allHooks.add(new ToolEventHook());

        log.info("★ SkillsAgentHook（{}技能）+ {} 治理 Hook（含 ToolEventHook）+ {} 工具 创建完成",
                skillsHook.getSkillCount(), allHooks.size() - 1, wrappedTools.length);

        return ReactAgent.builder()
                .name(AGENT_NAME).description(AGENT_DESCRIPTION)
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .hooks(allHooks)
                .tools(wrappedTools)
                .outputKey(OUTPUT_KEY)
                .enableLogging(true)
                .build();
    }
}
