package com.nbcb.agent.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.constant.PromptConstant;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.PromptService;
import com.nbcb.agent.skill.LoggingToolCallback;
import com.nbcb.agent.skill.NacosSkillLoader;
import com.nbcb.agent.skill.NacosSkillRegistry;
import com.nbcb.agent.skill.UnprefixedToolCallbackProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PostConstruct;

import java.util.List;

/**
 * Agent 核心配置 — ReactAgent + SkillsAgentHook + MCP 工具
 * <p>
 * ★ 核心架构变化（v2.0）：
 * <ul>
 *   <li><b>旧（文本注入）</b>：Skill 指令通过 {@code buildAgentMessage()} 拼接到用户消息中</li>
 *   <li><b>新（Hook 注入）</b>：使用框架原生 {@link SkillsAgentHook}，
 *       Agent 通过 {@code read_skill} 工具按需加载技能（渐进式上下文）</li>
 * </ul>
 * <p>
 * 工作流程：
 * <ol>
 *   <li>启动时 → NacosSkillRegistry 加载所有技能 → 转换为框架 SkillMetadata</li>
 *   <li>SkillsAgentHook.beforeAgent() → 将可用技能列表注入 system prompt</li>
 *   <li>LLM 根据用户请求，调用 read_skill("skill-name") 获取完整技能指令</li>
 *   <li>Agent 使用 .tools() 中注册的 MCP 工具 + 按技能指令执行</li>
 * </ol>
 * <p>
 * 工具注册策略：
 * <ul>
 *   <li>MCP 工具直接通过 {@code .tools()} 注册（全部可用）</li>
 *   <li>SkillsAgentHook 通过 {@code .hooks()} 注入 read_skill 工具</li>
 *   <li><b>不使用 groupedTools</b>：避免与 .tools() 中的工具重复注册</li>
 * </ul>
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

    /** ★ 启动时将 Spring 管理的 ObjectMapper 注入 StreamEvent，确保序列化全局一致 */
    @PostConstruct
    public void initStreamEventMapper() {
        StreamEvent.setObjectMapper(objectMapper);
        LoggingToolCallback.setMetrics(metrics);
        log.info("★ StreamEvent ObjectMapper 已注入，LoggingToolCallback 监控已注入");
    }

    // ==================== Agent 配置常量 ====================

    /** Agent 名称 */
    private static final String AGENT_NAME = "skill-agent";

    /** Agent 描述 */
    private static final String AGENT_DESCRIPTION =
            "基于 Nacos AI Registry 的技能驱动智能助手，使用 read_skill 工具按需加载技能";

    /** Graph 输出 key */
    private static final String OUTPUT_KEY = "agent_result";

    /** 默认系统提示词（Nacos 和本地文件均不可用时的最终兜底） */
    private static final String FALLBACK_SYSTEM_PROMPT = "你是一个技能驱动的智能助手。";

    // ==================== Bean 定义 ====================
    @Bean
    @Primary
    public ToolCallbackProvider unprefixedMcpTools(
            @Autowired(required = false)
            @Qualifier("distributedAsyncToolCallback") ToolCallbackProvider distributedTools) {
        if (distributedTools != null) {
            return new UnprefixedToolCallbackProvider(distributedTools);
        }
        return () -> new ToolCallback[0];
    }

    /**
     * ★ Nacos 技能注册表 Bean
     * <p>
     * 桥接 Nacos AI Registry → 框架 SkillRegistry 接口。
     * 构造时立即预加载 NacosSkillLoader 中所有已加载的技能。
     */
    @Bean
    public NacosSkillRegistry nacosSkillRegistry(NacosSkillLoader skillLoader) {
        return new NacosSkillRegistry(skillLoader);
    }

    /**
     * ★ 单一 ReactAgent — 包含 SkillsAgentHook + 全部 MCP 工具
     * <p>
     * SkillsAgentHook 提供：
     * <ul>
     *   <li><b>read_skill 工具</b> → LLM 按需读取技能完整指令（SKILL.md）</li>
     *   <li><b>beforeAgent 钩子</b> → 将可用技能列表注入 system prompt</li>
     * </ul>
     * <p>
     * 全量 MCP 工具通过 {@code .tools()} 注册，始终可用。
     * 不使用 groupedTools（避免工具重复注册）。
     *
     * @param chatModel     DeepSeek Chat Model（OpenAI 兼容）
     * @param mcpTools      去前缀 MCP 工具（分布式发现 → 去掉 m_s_ 前缀）
     * @param skillRegistry Nacos 技能注册表
     * @return 配置完成的 ReactAgent
     */
    @Bean
    public ReactAgent reactAgent(
            ChatModel chatModel,
            @Autowired(required = false) ToolCallbackProvider mcpTools,
            NacosSkillRegistry skillRegistry,
            PromptService promptService) {

        // ★ 0. 从 PromptService 获取系统提示词（Nacos 优先 → 本地文件兜底）
        String systemPrompt = promptService.getSystemPrompt(PromptConstant.AGENT_SYSTEM);
        log.info("★ 系统提示词来源: {}", promptService.isNacosAvailable() ? "Nacos" : "本地文件");

        // ★ 1. 全量 MCP 工具（装饰 LoggingToolCallback 用于追踪，记录通过 ThreadLocal 隔离）
        ToolCallback[] allTools;
        if (mcpTools != null && mcpTools.getToolCallbacks().length > 0) {
            allTools = LoggingToolCallback.wrapAll(mcpTools.getToolCallbacks());
            log.info("已装饰 {} 个 MCP 工具（LoggingToolCallback）", allTools.length);
        } else {
            allTools = new ToolCallback[0];
            log.warn("未检测到 MCP 工具，Agent 将以无工具模式运行");
        }

        // ★ 2. 构建 SkillsAgentHook（提供 read_skill 工具 + 技能元数据注入）
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(true)       // 每次 beforeAgent 自动 reload（配合 60s 定时刷新）
                .build();

        log.info("★ SkillsAgentHook 创建完成：{} 个技能", skillsHook.getSkillCount());

        // ★ 3. 构建单一 ReactAgent
        log.info("构建 ReactAgent：model={}, mcpTools={}, skills={}",
                chatModel.getClass().getCanonicalName(),
                allTools.length,
                skillsHook.getSkillCount());

        return ReactAgent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .model(chatModel)
                .systemPrompt(systemPrompt != null ? systemPrompt : FALLBACK_SYSTEM_PROMPT)
                .hooks(List.of(skillsHook))
                .tools(allTools)
                .outputKey(OUTPUT_KEY)
                .enableLogging(true)
                .build();
    }
}