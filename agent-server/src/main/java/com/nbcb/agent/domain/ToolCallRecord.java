package com.nbcb.agent.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具调用记录 POJO
 * <p>
 * 记录 Agent 每次工具调用的完整信息，包括工具名称、入参、出参和执行状态。
 * 被以下组件共用：
 * <ul>
 *   <li>{@link com.nbcb.agent.skill.LoggingToolCallback} — 装饰器中收集调用记录</li>
 *   <li>{@link AgentChatResponse} — API 响应的工具调用追踪列表</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallRecord {

    /** 工具名称 */
    private String toolName;

    /** 工具入参（JSON 字符串） */
    private String input;

    /** 工具出参（JSON 字符串，截断至 500 字符） */
    private String output;

    /** 调用是否成功 */
    private Boolean success;

    /** 错误信息（调用失败时） */
    private String error;
}
