package com.nbcb.agent.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.nbcb.agent.domain.AgentChatResponse;
import com.nbcb.agent.domain.RequestContext;
import com.nbcb.agent.skill.dynamic.DynamicSkillException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agent 对话编排服务 — ReactAgent + SkillsAgentHook（read_skill 按需加载）
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class AgentService {

    /** ReactAgent（含 SkillsAgentHook，提供 read_skill 工具） */
    private final ReactAgent agent;

    /** 最大重试次数（应对 LLM API 瞬时故障） */
    @Value("${agent.retry.max-attempts:2}")
    private int maxRetryAttempts;

    /** 重试间隔（毫秒） */
    @Value("${agent.retry.delay-ms:1000}")
    private long retryDelayMs;

    public AgentService(ReactAgent agent) {
        this.agent = agent;
    }

    /**
     * 处理 Agent 对话请求
     *
     * @param question 用户问题
     * @return 对话响应（含工具调用追踪 + Agent 最终回答）
     */
    public AgentChatResponse chat(String question) {
        long startTime = System.currentTimeMillis();

        log.info("★ Agent 对话开始 — question={}", question);

        // ★ 用户消息：直接传入问题文本，不强制指定技能
        String userMessage = question;

        // ★ 使用 try-with-resources 统一管理请求级状态
        try (RequestContext context = RequestContext.begin(null)) {
            AssistantMessage response = callWithRetry(userMessage);
            String answer = response.getText();
            long processingTime = System.currentTimeMillis() - startTime;

            log.info("★ Agent 对话完成 — 耗时 {}ms, LLM调用技能: {}, 工具调用 {} 次",
                    processingTime, context.getCalledSkills(), context.getToolRecords().size());

            return AgentChatResponse.builder()
                    .question(question)
                    .matchedSkill(context.getCalledSkills().isEmpty() ? null : context.getCalledSkills().get(0))
                    .calledSkills(context.getCalledSkills().isEmpty() ? null : context.getCalledSkills())
                    .toolCalls(context.getToolRecords().isEmpty() ? null : context.getToolRecords())
                    .answer(answer)
                    .processingTimeMs(processingTime)
                    .build();
        } catch (GraphRunnerException e) {
            DynamicSkillException dynamicSkillException = DynamicSkillException.findCause(e);
            if (dynamicSkillException != null) {
                throw dynamicSkillException;
            }
            throw new RuntimeException("Agent 执行异常: " + e.getMessage(), e);
        }
    }

    /**
     * ★ 带分类的重试 — 只重试瞬态错误（429/503/超时/连接），不可重试错误直接抛出
     * <p>
     * 重试策略：指数退避（1s → 2s → 4s），避免对 API 服务造成冲击
     */
    private AssistantMessage callWithRetry(String userMessage) throws GraphRunnerException {
        for (int attempt = 0; attempt <= maxRetryAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    long delay = retryDelayMs * (1L << (attempt - 1)); // 指数退避: 1s, 2s, 4s...
                    log.info("★ Agent 重试 {}/{}（指数退避 {}ms）", attempt, maxRetryAttempts, delay);
                    Thread.sleep(delay);
                }
                return agent.call(userMessage);
            } catch (GraphRunnerException e) {
                if (!isRetryable(e) || attempt >= maxRetryAttempts) {
                    throw e;
                }
                log.warn("★ Agent 调用失败（可重试）: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Agent 重试被中断", e);
            }
        }
        throw new GraphRunnerException("Agent 调用失败，已重试 " + maxRetryAttempts + " 次");
    }

    /**
     * ★ 判断异常是否可重试（仅对已知的瞬态错误重试）
     */
    private boolean isRetryable(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("429") || msg.contains("503") || msg.contains("502")
                || msg.contains("504") || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("connect") || msg.contains("connection reset")
                || msg.contains("too many request");
    }
}
