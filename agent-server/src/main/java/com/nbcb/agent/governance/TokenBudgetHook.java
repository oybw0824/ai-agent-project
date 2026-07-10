package com.nbcb.agent.governance;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.nbcb.agent.exception.AgentEarlyTerminationException;
import com.nbcb.agent.metric.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Token 预算 Hook
 * <p>
 * 在每次模型调用前估算累计 Token 消耗，超过预算时终止会话。
 * 使用简单字符数估算（中文 ~1 token/字，英文 ~0.25 token/字），
 * 综合取 {@code length / 2} 作为粗略估计。
 * <p>
 * ★ 设计说明：
 * <ul>
 *   <li>从 {@code state.data().get("messages")} 中提取所有消息文本</li>
 *   <li>估算累计 Token 数 = 所有消息总字符数 / 2</li>
 *   <li>超过预算时抛出 {@link AgentEarlyTerminationException}（REASON_TOKEN_BUDGET）</li>
 *   <li>达到警告比例（80%）时记录日志</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
public class TokenBudgetHook extends ModelHook {

    /** ★ 外部存储：会话累计 token（key=sessionId, value=estimatedTokens），解决 state.data() 不可变问题 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> SESSION_TOKEN_USED = new java.util.concurrent.ConcurrentHashMap<>();

    private final AgentGovernanceProperties properties;
    private final AgentMetrics metrics;

    public TokenBudgetHook(AgentGovernanceProperties properties, AgentMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "TokenBudgetHook";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Map<String, Object> stateData = state.data();
        String sessionId = config.threadId().orElse("unknown");

        // 估算当前累计 Token 消耗，存储到外部 ConcurrentHashMap
        int estimatedTokens = estimateCurrentTokens(stateData);
        SESSION_TOKEN_USED.put(sessionId, estimatedTokens);

        int maxTokens = properties.getTokenBudget().getDefaultMaxTokens();
        double warningRatio = properties.getTokenBudget().getWarningRatio();

        // 超过预算
        if (estimatedTokens > maxTokens) {
            log.warn("Token 预算超限 [session={}]: used={}, limit={}",
                    sessionId, estimatedTokens, maxTokens);
            if (metrics != null) {
                metrics.governanceTokenBudget.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_TOKEN_BUDGET,
                    "会话 Token 消耗超过预算（" + estimatedTokens + " / " + maxTokens + "）",
                    sessionId);
        }

        // 达到警告比例
        if (estimatedTokens > maxTokens * warningRatio) {
            log.warn("Token 使用量接近上限 [session={}]: used={}, limit={}, ratio={:.0%}",
                    sessionId, estimatedTokens, maxTokens, (double) estimatedTokens / maxTokens);
        }

        return CompletableFuture.completedFuture(
                Map.of("tokenUsed", estimatedTokens, "tokenLimit", maxTokens));
    }

    /**
     * 从 state 中估算当前累计 Token 数
     * <p>
     * ★ 简化策略：读取所有 messages 的文本内容，用 字符数/2 粗略估计 token 数。
     * 相比 tokenizer，此方法零依赖、零性能开销，适合作为预算控制的估算手段。
     *
     * @param stateData 会话状态数据
     * @return 估算的 Token 数
     */
    @SuppressWarnings("unchecked")
    private int estimateCurrentTokens(Map<String, Object> stateData) {
        Object messagesObj = stateData.get("messages");
        if (!(messagesObj instanceof List)) {
            return 0;
        }

        List<Message> messages = (List<Message>) messagesObj;
        int totalChars = 0;

        for (Message msg : messages) {
            String text = extractText(msg);
            if (text != null) {
                totalChars += text.length();
            }
        }

        // 简单估算：字符数 / 2 ≈ token 数
        return totalChars / 2;
    }

    /**
     * 从消息中提取文本内容
     */
    private String extractText(Message message) {
        if (message instanceof AssistantMessage assistantMsg) {
            return assistantMsg.getText();
        } else if (message instanceof UserMessage userMsg) {
            return userMsg.getText();
        } else if (message instanceof ToolResponseMessage toolMsg) {
            // 工具返回消息
            return toolMsg.getResponses().stream()
                    .map(r -> r.responseData())
                    .reduce("", (a, b) -> a + b);
        }
        // 其他类型消息使用 toString
        String text = message.getText();
        return text != null ? text : "";
    }
}
