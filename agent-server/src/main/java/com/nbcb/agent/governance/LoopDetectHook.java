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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 空转检测 Hook
 * <p>
 * 在每次模型调用前分析最近的工具调用历史，检测三种空转模式：
 * <ol>
 *   <li><b>连续重复</b>：同一工具+相同参数连续调用 ≥ N 次</li>
 *   <li><b>交替重复</b>：A→B→A→B 模式在滑动窗口内出现</li>
 *   <li><b>降级后重试</b>：工具返回 nonRetryable 结果后仍被调用</li>
 * </ol>
 * <p>
 * ★ 工具调用历史从 {@code state.data().get("messages")} 提取，
 * 通过分析 {@link AssistantMessage#getText()} 中的工具调用内容和
 * {@link ToolResponseMessage} 中的工具响应记录来追踪调用序列。
 *
 * @author com.nbcb
 */
@Slf4j
public class LoopDetectHook extends ModelHook {

    private final AgentGovernanceProperties properties;
    private final AgentMetrics metrics;

    public LoopDetectHook(AgentGovernanceProperties properties, AgentMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "LoopDetectHook";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        String sessionId = config.threadId().orElse("unknown");
        int window = properties.getLoopDetect().getPatternWindow();
        int strictThreshold = properties.getLoopDetect().getStrictRepeatThreshold();
        int altThreshold = properties.getLoopDetect().getAlternatingRepeatThreshold();

        // 从 messages 提取最近的工具调用签名列表
        List<String> recentSignatures = extractRecentToolSignatures(state, window);
        if (recentSignatures.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // 检测 1：连续重复调用
        if (isStrictRepeating(recentSignatures, strictThreshold)) {
            log.warn("检测到连续重复工具调用 [session={}]: signatures={}",
                    sessionId, recentSignatures.subList(
                            Math.max(0, recentSignatures.size() - strictThreshold),
                            recentSignatures.size()));
            if (metrics != null) {
                metrics.governanceLoopDetect.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_LOOP_DETECT,
                    "检测到连续重复工具调用（已连续 " + strictThreshold + " 次相同调用）",
                    sessionId);
        }

        // 检测 2：交替模式
        if (isAlternatingPattern(recentSignatures, altThreshold)) {
            log.warn("检测到交替模式空转 [session={}]: signatures={}",
                    sessionId, recentSignatures);
            if (metrics != null) {
                metrics.governanceLoopDetect.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_LOOP_DETECT,
                    "检测到交替模式空转（工具调用陷入 A→B→A→B 循环）",
                    sessionId);
        }

        // 检测 3：降级后重试
        if (properties.getLoopDetect().isStopAfterNonRetryableResult()
                && hasRepeatedCallAfterNonRetryableResult(state)) {
            log.warn("检测到不可重试结果后仍调用同一工具 [session={}]", sessionId);
            if (metrics != null) {
                metrics.governanceLoopDetect.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_LOOP_DETECT,
                    "工具已返回不可重试结果，停止继续尝试",
                    sessionId);
        }

        return CompletableFuture.completedFuture(Map.of());
    }

    // ==================== 工具调用签名提取 ====================

    /**
     * 从 state 的 messages 中提取最近的工具调用签名列表
     * <p>
     * ★ 通过解析 AssistantMessage 文本中的工具调用标记和
     * ToolResponseMessage 中的工具名称来构建调用序列。
     * 签名格式：{@code toolName}
     * （简化方案：仅基于工具名检测，参数规范化可在后续版本增强）
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRecentToolSignatures(OverAllState state, int window) {
        Object messagesObj = state.data().get("messages");
        if (!(messagesObj instanceof List)) {
            return new ArrayList<>();
        }

        List<Message> messages = (List<Message>) messagesObj;
        List<String> signatures = new ArrayList<>();

        for (Message msg : messages) {
            // 从 ToolResponseMessage 提取工具名（最可靠的来源）
            if (msg instanceof ToolResponseMessage toolResponseMsg) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMsg.getResponses()) {
                    String toolName = response.name();
                    if (toolName != null && !toolName.isEmpty()) {
                        signatures.add(toolName);
                    }
                }
            }
            // 从 AssistantMessage 的文本中解析工具调用
            else if (msg instanceof AssistantMessage assistantMsg) {
                String text = assistantMsg.getText();
                if (text != null) {
                    // 尝试从文本中提取工具调用名（ReAct 模式下的典型格式）
                    List<String> parsed = parseToolNamesFromText(text);
                    signatures.addAll(parsed);
                }
            }
        }

        // 只保留最近 window 条记录
        if (signatures.size() > window) {
            signatures = signatures.subList(signatures.size() - window, signatures.size());
        }
        return signatures;
    }

    /**
     * 从 AssistantMessage 文本中解析工具调用名
     * <p>
     * ★ 支持常见的 ReAct 工具调用格式：
     * <ul>
     *   <li>XML 格式：{@code <tool_call>{"name": "xxx", ...}</tool_call>}</li>
     *   <li>JSON 格式：{@code {"name": "xxx", "arguments": {...}}}</li>
     * </ul>
     */
    private List<String> parseToolNamesFromText(String text) {
        List<String> toolNames = new ArrayList<>();
        // 匹配 "name": "xxx" 或 "name":"xxx" 模式（JSON 格式的工具调用声明）
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            toolNames.add(matcher.group(1));
        }
        return toolNames;
    }

    // ==================== 检测算法 ====================

    /**
     * 检测连续重复调用：最近 N 次调用的签名完全相同
     */
    private boolean isStrictRepeating(List<String> signatures, int threshold) {
        if (signatures.size() < threshold) {
            return false;
        }
        int start = signatures.size() - threshold;
        String first = signatures.get(start);
        for (int i = start + 1; i < signatures.size(); i++) {
            if (!signatures.get(i).equals(first)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检测交替重复模式：A→B→A→B 至少重复 threshold 次
     */
    private boolean isAlternatingPattern(List<String> signatures, int threshold) {
        if (signatures.size() < threshold * 2) {
            return false;
        }
        int start = signatures.size() - threshold * 2;
        String a = signatures.get(start);
        String b = signatures.get(start + 1);
        if (a.equals(b)) {
            return false;
        }
        for (int i = start; i < signatures.size(); i++) {
            String expected = ((i - start) % 2 == 0) ? a : b;
            if (!signatures.get(i).equals(expected)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检测不可重试结果后仍调用同一工具
     * <p>
     * 扫描 ToolResponseMessage 查找包含 {@code "retryable":false} 标记的响应，
     * 然后检查之后是否出现了对同一工具的调用。
     */
    @SuppressWarnings("unchecked")
    private boolean hasRepeatedCallAfterNonRetryableResult(OverAllState state) {
        Object messagesObj = state.data().get("messages");
        if (!(messagesObj instanceof List)) {
            return false;
        }

        List<Message> messages = (List<Message>) messagesObj;
        // toolName -> 最后一次返回 nonRetryable 的消息索引
        Map<String, Integer> nonRetryableTools = new HashMap<>();

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof ToolResponseMessage toolMsg) {
                for (ToolResponseMessage.ToolResponse response : toolMsg.getResponses()) {
                    String responseData = response.responseData();
                    if (responseData != null && responseData.contains("\"retryable\":false")) {
                        nonRetryableTools.put(response.name(), i);
                    }
                }
            }
        }

        // 检查 nonRetryable 结果之后是否还有对同一工具的调用
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage assistantMsg) {
                String text = assistantMsg.getText();
                if (text != null) {
                    List<String> toolNames = parseToolNamesFromText(text);
                    for (String toolName : toolNames) {
                        Integer nonRetryableIndex = nonRetryableTools.get(toolName);
                        if (nonRetryableIndex != null && i > nonRetryableIndex) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}
