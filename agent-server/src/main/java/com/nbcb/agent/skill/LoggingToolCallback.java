package com.nbcb.agent.skill;

import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.domain.ToolCallRecord;
import com.nbcb.agent.metric.AgentMetrics;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用装饰器 — 拦截并记录每次 MCP 工具调用
 * <p>
 * 包装真实的 {@link ToolCallback}，在调用前后记录 工具名、入参、出参、成功/失败。
 * 通过静态 {@link ThreadLocal} 存储当前请求的记录列表和 SSE 发射器，天然支持并发。
 * <p>
 * ★ 新增 SSE 流式支持：当传入 {@link SseEmitter} 时，工具调用的开始/结束会实时推送事件。
 * <p>
 * 调用方需在请求开始/结束时调用 {@link #beginRecording()} / {@link #endRecording()}：
 * <pre>
 * LoggingToolCallback.beginRecording();
 * try {
 *     agent.call(userMessage);
 * } finally {
 *     List&lt;ToolCallRecord&gt; records = LoggingToolCallback.endRecording();
 * }
 * </pre>
 * 流式调用时还需设置 SSE 发射器：
 * <pre>
 * LoggingToolCallback.beginRecording();
 * LoggingToolCallback.setSseEmitter(emitter);  // ★ 设置 SSE 回调
 * try { ... } finally { ... }
 * </pre>
 *
 * @author com.nbcb
 */
@Slf4j
public class LoggingToolCallback implements ToolCallback {

    /** ★ 线程级工具调用记录（ThreadLocal，支持并发请求隔离） */
    private static final ThreadLocal<List<ToolCallRecord>> CURRENT_RECORDS = new ThreadLocal<>();

    /** ★ 线程级 SSE 发射器（流式推送用，非流式时为 null） */
    private static final ThreadLocal<SseEmitter> CURRENT_EMITTER = new ThreadLocal<>();

    /** ★ 监控埋点（由 Spring 启动时注入） */
    private static volatile AgentMetrics METRICS;

    public static void setMetrics(AgentMetrics metrics) {
        METRICS = metrics;
    }

    private final ToolCallback delegate;

    public LoggingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        long start = System.currentTimeMillis();

        if (METRICS != null) {
            METRICS.toolCallTotal.increment();
        }

        ToolCallRecord record = ToolCallRecord.builder()
                .toolName(toolName)
                .input(toolInput)
                .build();

        pushSseEvent(StreamEvent.toolCall(toolName, toolInput));

        try {
            String result = delegate.call(toolInput, toolContext);
            record.setOutput(truncate(result, 500));
            record.setSuccess(true);
            if (METRICS != null) {
                METRICS.toolCallSuccess.increment();
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("MCP 工具调用成功 [{}] input={}, output={}, elapsed={}ms",
                    toolName, toolInput, truncate(result, 200), elapsed);
            pushSseEvent(StreamEvent.toolResult(toolName, truncate(result, 200)));
            return result;
        } catch (Exception e) {
            record.setSuccess(false);
            record.setError(e.getMessage());
            if (METRICS != null) {
                METRICS.toolCallFailure.increment();
            }
            log.warn("MCP 工具调用失败 [{}] input={}, error={}", toolName, toolInput, e.getMessage());
            throw e;
        } finally {
            List<ToolCallRecord> records = CURRENT_RECORDS.get();
            if (records != null) {
                records.add(record);
            }
        }
    }

    // ==================== ★ SSE 流式推送 ====================

    /**
     * 设置当前线程的 SSE 发射器（流式调用时设置）
     */
    public static void setSseEmitter(SseEmitter emitter) {
        CURRENT_EMITTER.set(emitter);
    }

    /**
     * 向当前线程的 SSE 发射器推送事件
     */
    private static void pushSseEvent(StreamEvent event) {
        SseEmitter emitter = CURRENT_EMITTER.get();
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType().name().toLowerCase())
                        .data(event.toJson()));
            } catch (IOException e) {
                log.debug("SSE 推送失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
    }

    // ==================== ★ ThreadLocal 生命周期管理 ====================

    /**
     * 开始记录（请求开始时调用）
     * <p>
     * 为当前线程初始化一个新的记录列表，调用方需在 finally 中调用 {@link #endRecording()} 清理。
     */
    public static void beginRecording() {
        CURRENT_RECORDS.set(new ArrayList<>());
    }

    /**
     * 结束记录并返回快照（请求结束时调用）
     * <p>
     * 清理 ThreadLocal 后返回不可变快照，防止内存泄漏。
     *
     * @return 本次请求中所有工具调用记录的快照
     */
    public static List<ToolCallRecord> endRecording() {
        List<ToolCallRecord> records = CURRENT_RECORDS.get();
        CURRENT_RECORDS.remove();
        CURRENT_EMITTER.remove();  // ★ 清理 SSE 发射器，防止内存泄漏
        return records != null ? List.copyOf(records) : List.of();
    }

    /**
     * 批量装饰 ToolCallback 数组
     */
    public static ToolCallback[] wrapAll(ToolCallback[] originals) {
        ToolCallback[] wrapped = new ToolCallback[originals.length];
        for (int i = 0; i < originals.length; i++) {
            wrapped[i] = new LoggingToolCallback(originals[i]);
        }
        return wrapped;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

}