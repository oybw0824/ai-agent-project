package com.nbcb.agent.service.stream;

import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;

/**
 * 流式文本收集器 — 收集 Agent 流式响应的文本内容
 * <p>
 * 累积 Agent 返回的流式文本块，提供增量提取功能。
 * 支持记录最后的 OverAllState，用于从状态中提取最终答案。
 *
 * @author com.nbcb
 */
@Slf4j
public class StreamingTextCollector {

    /** 最大流式文本长度，防止 LLM 输出失控导致 OOM */
    private static final int MAX_LENGTH = 100_000;

    /** 文本构建器 */
    private final StringBuilder builder = new StringBuilder();

    /** 最后的状态节点 */
    private OverAllState lastState;

    /**
     * 构造函数
     */
    public StreamingTextCollector() {
        // 无参构造
    }

    /**
     * 追加文本增量
     * <p>
     * 如果追加后超过最大长度，静默截断，避免 OOM。
     *
     * @param delta 文本增量
     */
    public void append(String delta) {
        if (builder.length() + delta.length() > MAX_LENGTH) {
            log.warn("★ 流式文本超过最大长度 {}，静默截断", MAX_LENGTH);
            return;
        }
        builder.append(delta);
    }

    /**
     * 获取累积的文本
     *
     * @return 累积文本
     */
    public String getAnswer() {
        return builder.toString();
    }

    /**
     * 获取当前文本长度
     *
     * @return 文本长度
     */
    public int length() {
        return builder.length();
    }

    /**
     * 设置最后的状态节点
     *
     * @param state OverAllState
     */
    public void setLastState(OverAllState state) {
        this.lastState = state;
    }

    /**
     * 获取最后的状态节点
     *
     * @return OverAllState
     */
    public OverAllState getLastState() {
        return lastState;
    }

    /**
     * 获取最大长度
     *
     * @return 最大长度
     */
    public static int getMaxLength() {
        return MAX_LENGTH;
    }
}