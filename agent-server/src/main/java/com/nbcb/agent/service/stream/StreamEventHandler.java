package com.nbcb.agent.service.stream;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.nbcb.agent.domain.StreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式事件处理器 — 处理 Agent 流式输出事件
 * <p>
 * 负责解析和处理 ReactAgent 返回的 NodeOutput，将不同类型的事件
 * （文本增量、节点切换）转换为 SSE 事件并推送到客户端。
 *
 * @author com.nbcb
 */
@Slf4j
public final class StreamEventHandler {

    private StreamEventHandler() {
        // 工具类，禁止实例化
    }

    /**
     * 处理节点输出
     * <p>
     * 根据输出类型执行不同处理：
     * <ul>
     *   <li>{@link StreamingOutput} — 流式文本增量</li>
     *   <li>普通节点 — 节点切换事件</li>
     *   <li>状态更新 — 记录最后状态</li>
     * </ul>
     *
     * @param collector  文本收集器
     * @param nodeOutput 节点输出
     * @param pushCallback SSE 推送回调
     * @return true 表示这是流式文本增量（用于判断是否需要后续补发 message 事件）
     */
    public static boolean handleNodeOutput(StreamingTextCollector collector,
                                           NodeOutput nodeOutput,
                                           PushCallback pushCallback) {
        // 更新最后状态
        if (nodeOutput.state() != null) {
            collector.setLastState(nodeOutput.state());
        }

        // 处理流式输出
        if (nodeOutput instanceof StreamingOutput<?> streamingOutput) {
            return handleStreamingOutput(collector, streamingOutput, pushCallback);
        }

        // 处理普通节点切换
        handleNodeChange(nodeOutput, pushCallback);
        return false;
    }

    /**
     * 处理流式文本输出
     *
     * @param collector      文本收集器
     * @param streamingOutput 流式输出
     * @param pushCallback   SSE 推送回调
     * @return true 表示推送了文本增量
     */
    private static boolean handleStreamingOutput(StreamingTextCollector collector,
                                                  StreamingOutput<?> streamingOutput,
                                                  PushCallback pushCallback) {
        String chunk = streamingOutput.chunk();
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }

        String delta = extractDelta(collector, chunk);
        if (delta.isEmpty()) {
            return false;
        }

        collector.append(delta);
        pushCallback.push(StreamEvent.text(delta));
        return true;
    }

    /**
     * 处理节点切换
     *
     * @param nodeOutput  节点输出
     * @param pushCallback SSE 推送回调
     */
    private static void handleNodeChange(NodeOutput nodeOutput, PushCallback pushCallback) {
        log.info("Graph Node: node={}, isEND={}", nodeOutput.node(), nodeOutput.isEND());
        pushCallback.push(StreamEvent.node(nodeOutput.node(), nodeOutput.isEND()));
    }

    /**
     * 基于位置追踪的增量提取（O(1) 而非 O(n) 子串比较）
     * <p>
     * 兼容两种 chunk 模式：
     * <ul>
     *   <li>累积模式：chunk 是完整文本，提取从 prevLength 之后的新内容</li>
     *   <li>增量模式：chunk 是纯增量，直接返回</li>
     * </ul>
     *
     * @param collector 文本收集器
     * @param chunk     LLM 返回的文本块
     * @return 文本增量
     */
    private static String extractDelta(StreamingTextCollector collector, String chunk) {
        int prevLength = collector.length();
        if (prevLength == 0) {
            return chunk;
        }
        if (chunk.length() <= prevLength) {
            // 纯增量模式：chunk 本身就是新增内容
            return chunk;
        }
        // 累积模式：提取增量部分
        return chunk.substring(prevLength);
    }

    /**
     * SSE 推送回调接口
     */
    @FunctionalInterface
    public interface PushCallback {
        /**
         * 推送事件
         *
         * @param event 流式事件
         */
        void push(StreamEvent event);
    }
}