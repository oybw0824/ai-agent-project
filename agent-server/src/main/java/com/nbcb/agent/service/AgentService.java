package com.nbcb.agent.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.nbcb.agent.metric.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.domain.AgentChatResponse;
import com.nbcb.agent.domain.ToolCallRecord;
import com.nbcb.agent.skill.LoggingToolCallback;
import com.nbcb.agent.skill.NacosSkillRegistry;
import com.nbcb.agent.util.JsonRetryHelper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 对话编排服务
 * <p>
 * ★ v2.1 架构：LLM 自主匹配技能（非强制指定）
 * <ol>
 *   <li>用户问题直接传入 Agent，不携带 skillId</li>
 *   <li>ReactAgent 内置 SkillsAgentHook → 注入可用技能列表 + 匹配规则到 system prompt</li>
 *   <li>LLM 根据 system prompt 规则，自主匹配并调用 <b>read_skill</b> 加载技能</li>
 *   <li>Agent 按技能指令执行 → 调用 MCP 工具 → 返回结果</li>
 * </ol>
 * <p>
 * 与旧架构的核心区别：
 * <ul>
 *   <li><b>v2.0</b>：用户消息强制携带 skillId（"请使用 {skillId} 技能处理…"）</li>
 *   <li><b>v2.1</b>：用户消息仅含问题文本，LLM 自主匹配技能</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class AgentService {

    /** ★ 单一 ReactAgent（包含 SkillsAgentHook，处理所有技能） */
    private final ReactAgent agent;

    private final AgentMetrics metrics;

    /** 最大重试次数（应对 LLM API 瞬时故障） */
    @Value("${agent.retry.max-attempts:2}")
    private int maxRetryAttempts;

    /** 重试间隔（毫秒） */
    @Value("${agent.retry.delay-ms:1000}")
    private long retryDelayMs;

    public AgentService(ReactAgent agent, AgentMetrics metrics) {
        this.agent = agent;
        this.metrics = metrics;
    }

    /**
     * 处理 Agent 对话请求
     * <p>
     * ★ 新流程（v2.1）：
     * <ol>
     *   <li>用户问题直接传入 Agent，不强制指定 skillId</li>
     *   <li>SkillsAgentHook 注入可用技能列表 + read_skill 工具到 system prompt</li>
     *   <li>LLM 根据 system prompt 中的技能匹配规则，自主选择最适技能</li>
     *   <li>LLM 通过 read_skill 工具按需加载技能完整指令</li>
     *   <li>Agent 按技能指令执行 → 调用 MCP 工具 → 返回结果</li>
     * </ol>
     * <p>
     * 与旧架构的关键区别：
     * <ul>
     *   <li><b>v2.0</b>：用户消息强制携带 skillId（"请使用 {skillId} 技能处理…"）</li>
     *   <li><b>v2.1</b>：用户消息仅含问题文本，LLM 自主匹配技能</li>
     * </ul>
     *
     * @param question 用户问题
     * @return 对话响应（含工具调用追踪 + LLM 实际调用的技能 + Agent 最终回答）
     */
    public AgentChatResponse chat(String question) {
        long startTime = System.currentTimeMillis();
        metrics.chatTotal.increment();

        log.info("★ Agent 对话开始 — question={}", question);

        // ★ 用户消息：直接传入问题文本，不强制指定技能
        String userMessage = question;

        // ★ 开启当前线程的工具调用记录
        LoggingToolCallback.beginRecording();
        NacosSkillRegistry.beginRecording();

        String answer;
        List<ToolCallRecord> records;
        List<String> calledSkills;
        try {
            AssistantMessage response = callWithRetry(userMessage);
            answer = response.getText();
            metrics.chatSuccess.increment();
        } catch (GraphRunnerException e) {
            metrics.chatFailure.increment();
            throw new RuntimeException("Agent 执行异常: " + e.getMessage(), e);
        } finally {
            records = LoggingToolCallback.endRecording();
            calledSkills = NacosSkillRegistry.endRecording();
        }

        long processingTime = System.currentTimeMillis() - startTime;
        metrics.chatDuration.record(processingTime, java.util.concurrent.TimeUnit.MILLISECONDS);

        log.info("★ Agent 对话完成 — 耗时 {}ms, LLM调用技能: {}, 工具调用 {} 次",
                processingTime, calledSkills, records.size());

        return AgentChatResponse.builder()
                .question(question)
                .matchedSkill(calledSkills.isEmpty() ? null : calledSkills.get(0))
                .calledSkills(calledSkills.isEmpty() ? null : calledSkills)
                .toolCalls(records.isEmpty() ? null : records)
                .answer(answer)
                .processingTimeMs(processingTime)
                .build();
    }

    /**
     * 带重试的 Agent 调用，应对 LLM API 瞬时故障（429 / 503 / 超时）
     */
    private AssistantMessage callWithRetry(String userMessage) throws GraphRunnerException {
        for (int attempt = 0; attempt <= maxRetryAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("★ Agent 重试 {}/{}", attempt, maxRetryAttempts);
                    metrics.chatRetry.increment();
                    Thread.sleep(retryDelayMs);
                }
                return agent.call(userMessage);
            } catch (GraphRunnerException e) {
                if (attempt >= maxRetryAttempts) {
                    throw e; // 最后一次也失败，抛出
                }
                log.warn("★ Agent 调用失败（将重试）: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GraphRunnerException("Agent 重试被中断", e);
            }
        }
        throw new GraphRunnerException("Agent 调用失败，已重试 " + maxRetryAttempts + " 次");
    }
}