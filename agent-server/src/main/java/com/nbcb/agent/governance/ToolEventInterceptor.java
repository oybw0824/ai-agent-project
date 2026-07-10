package com.nbcb.agent.governance;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.nbcb.agent.domain.RequestContext;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.domain.ToolCallRecord;
import com.nbcb.agent.util.SsePushHelper;
import lombok.extern.slf4j.Slf4j;

/**
 * ★ 工具事件拦截器 — 在 AgentToolNode 内部拦截所有工具调用，推送 SSE 事件并记录调用历史
 * <p>
 * 与 LoggingToolCallback（ToolCallback 包装）不同，此拦截器运行在框架的
 * {@link AgentToolNode#executeToolCallWithInterceptors} 内部，
 * 无论工具是通过 builder.tools() 还是 ToolCallbackResolver 解析，都会被执行。
 * <p>
 * ★ 职责：
 * <ul>
 *   <li>调用前：推送 {@code tool_call} SSE 事件（工具名 + 入参）</li>
 *   <li>调用后：推送 {@code tool_result} SSE 事件（工具名 + 出参）</li>
 *   <li>记录工具调用到 {@link RequestContext}（供 done 事件统计 toolCallCount）</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
public class ToolEventInterceptor extends ToolInterceptor {

    /** ★ 监控埋点（由 Spring 启动时注入） */
    private static volatile com.nbcb.agent.metric.AgentMetrics METRICS;

    /**
     * 设置监控埋点
     */
    public static void setMetrics(com.nbcb.agent.metric.AgentMetrics metrics) {
        METRICS = metrics;
    }

    @Override
    public String getName() {
        return "ToolEventInterceptor";
    }

    /** 工具出参最大长度（超过截断） */
    private static final int MAX_OUTPUT_LENGTH = 200;

    /** 工具入参最大长度（超过截断） */
    private static final int MAX_INPUT_LENGTH = 500;

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        String arguments = request.getArguments();

        // ★ 指标计数
        if (METRICS != null) {
            METRICS.toolCallTotal.increment();
        }

        // ★ 推送 tool_call SSE 事件
        pushSseEvent(StreamEvent.toolCall(toolName, truncate(arguments, MAX_INPUT_LENGTH)));

        long start = System.currentTimeMillis();
        ToolCallResponse response;
        try {
            response = handler.call(request);
        } catch (Exception e) {
            if (METRICS != null) METRICS.toolCallFailure.increment();
            log.warn("工具调用异常 [{}] elapsed={}ms", toolName, System.currentTimeMillis() - start, e);
            throw e;
        }
        long elapsed = System.currentTimeMillis() - start;

        if (response != null && !response.isError()) {
            if (METRICS != null) METRICS.toolCallSuccess.increment();
            pushSseEvent(StreamEvent.toolResult(toolName, truncate(response.getResult(), MAX_OUTPUT_LENGTH)));
            // ★ 工具参数/结果仅 DEBUG 级别记录，避免敏感信息泄露
            log.debug("工具调用成功 [{}] input={} output={} elapsed={}ms",
                    toolName, truncate(arguments, 100),
                    truncate(response.getResult(), 100), elapsed);
            log.info("工具调用成功 [{}] elapsed={}ms", toolName, elapsed);
        } else if (response != null) {
            if (METRICS != null) METRICS.toolCallFailure.increment();
            log.warn("工具返回错误 [{}] status={}", toolName, response.getStatus());
        } else {
            if (METRICS != null) METRICS.toolCallFailure.increment();
        }

        // ★ 记录工具调用到 RequestContext
        recordToolCall(toolName, arguments,
                response != null ? response.getResult() : null,
                response == null || !response.isError());

        return response;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 推送 SSE 事件（安全推送）
     *
     * @param event 流式事件
     */
    private void pushSseEvent(StreamEvent event) {
        RequestContext ctx = RequestContext.current();
        if (ctx != null && ctx.getEmitter() != null) {
            SsePushHelper.push(ctx.getEmitter(), event);
        }
    }

    /**
     * 记录工具调用到 RequestContext
     *
     * @param toolName 工具名
     * @param input    入参
     * @param output   出参
     * @param success  是否成功
     */
    private void recordToolCall(String toolName, String input, String output, boolean success) {
        RequestContext ctx = RequestContext.current();
        if (ctx == null) return;

        ToolCallRecord record = ToolCallRecord.builder()
                .toolName(toolName)
                .input(input)
                .build();
        record.setOutput(truncate(output, MAX_OUTPUT_LENGTH));
        record.setSuccess(success);
        ctx.recordToolCall(record);
    }

    /**
     * 截断字符串，防止日志/SSE 数据过大
     *
     * @param s      原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }
}
