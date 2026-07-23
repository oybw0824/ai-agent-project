package com.nbcb.agent.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;

/**
 * ★ SSE 流式事件 — Agent 思考过程的每一步
 * <p>
 * 事件类型映射 Agent 执行的各个阶段，前端按 type 渲染不同的 UI 组件：
 * <ul>
 *   <li>{@code thinking}  → Agent 开始分析，即将调用 LLM</li>
 *   <li>{@code node}      → Graph Node 执行完成（llm / tools）</li>
 *   <li>{@code text}       → LLM 实时 token 流式输出（逐字推送）</li>
 *   <li>{@code skill_load} → LLM 通过 read_skill 加载了某个技能</li>
 *   <li>{@code tool_call}  → LLM 决定调用 MCP 工具（含工具名 + 入参）</li>
 *   <li>{@code tool_result}→ MCP 工具返回结果</li>
 *   <li>{@code message}    → Agent 最终回答（文本块）</li>
 *   <li>{@code done}       → 对话完成（含完整元数据）</li>
 *   <li>{@code error}      → 发生错误</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Data
@Builder
@Slf4j
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEvent {

    /** ★ 使用 Spring 管理的 ObjectMapper（统一配置、避免静态实例不一致） */
    private static volatile ObjectMapper MAPPER;

    /**
     * ★ Spring 容器启动时注入 ObjectMapper，确保全局一致
     */
    public static void setObjectMapper(ObjectMapper mapper) {
        MAPPER = mapper;
    }

    private static ObjectMapper getMapper() {
        if (MAPPER == null) {
            // ★ 降级：Spring 尚未注入时使用内置实例
            synchronized (StreamEvent.class) {
                if (MAPPER == null) {
                    MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
                }
            }
        }
        return MAPPER;
    }

    /** 事件类型 */
    private EventType type;

    /** 事件消息（人类可读描述，前端直接展示） */
    private String message;

    /** 附加数据（根据事件类型不同，如工具名、入参、出参） */
    private Map<String, Object> data;

    /** 事件时间戳 */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * ★ SSE 事件类型枚举
     */
    public enum EventType {
        /** Agent 开始分析 */
        THINKING,
        /** Graph Node 执行完成 */
        NODE,
        /** LLM token 级实时流式输出（逐字/逐词推送） */
        TEXT,
        /** LLM 加载技能 */
        SKILL_LOAD,
        /** LLM 调用工具 */
        TOOL_CALL,
        /** 工具返回结果 */
        TOOL_RESULT,
        /** Agent 回答文本块 */
        MESSAGE,
        /** 对话完成 */
        DONE,
        /** 错误 */
        ERROR,
        /** Skill 生成阶段进度 */
        SKILL_STAGE
    }

    /**
     * 序列化为 JSON 字符串（用于 SSE data 行）
     */
    public String toJson() {
        try {
            return getMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            log.error("StreamEvent 序列化失败: type={}, message={}", this.type, this.message, e);
            return "{\"type\":\"ERROR\",\"message\":\"序列化失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ==================== 工厂方法 ====================

    public static StreamEvent thinking(String message) {
        return StreamEvent.builder()
                .type(EventType.THINKING)
                .message(message)
                .build();
    }

    /**
     * ★ Graph Node 执行完成事件（v2.2 新增）
     * @param nodeName 节点名称（如 "llm", "tools", "agent"）
     * @param isEnd    是否为最终节点
     */
    public static StreamEvent node(String nodeName, boolean isEnd) {
        return StreamEvent.builder()
                .type(EventType.NODE)
                .message("节点执行: " + nodeName)
                .data(Map.of("nodeName", nodeName, "isEnd", isEnd))
                .build();
    }

    public static StreamEvent skillLoad(String skillName, int contentLength) {
        return StreamEvent.builder()
                .type(EventType.SKILL_LOAD)
                .message("加载技能: " + skillName)
                .data(Map.of("skillName", skillName, "contentLength", contentLength))
                .build();
    }

    public static StreamEvent toolCall(String toolName, String input) {
        return StreamEvent.builder()
                .type(EventType.TOOL_CALL)
                .message("调用工具: " + toolName)
                .data(Map.of("toolName", toolName, "input", input))
                .build();
    }

    public static StreamEvent toolResult(String toolName, String output) {
        return StreamEvent.builder()
                .type(EventType.TOOL_RESULT)
                .message("工具返回: " + toolName)
                .data(Map.of("toolName", toolName, "output", output))
                .build();
    }

    /**
     * ★ LLM token 级流式文本增量（v2.3 新增）
     * <p>
     * 与 {@link #message(String)} 的区别：
     * <ul>
     *   <li>{@code text} — 来自 {@code StreamingOutput.chunk()}，逐 token 实时推送</li>
     *   <li>{@code message} — Agent 完整回答块，用于整段展示</li>
     * </ul>
     *
     * @param text token 文本片段（如 "Hello"、" World"、"!"）
     */
    public static StreamEvent text(String text) {
        return StreamEvent.builder()
                .type(EventType.TEXT)
                .message(text)
                .build();
    }

    public static StreamEvent message(String text) {
        return StreamEvent.builder()
                .type(EventType.MESSAGE)
                .message(text)
                .build();
    }

    public static StreamEvent done(Map<String, Object> metadata) {
        return StreamEvent.builder()
                .type(EventType.DONE)
                .message("对话完成")
                .data(metadata)
                .build();
    }

    public static StreamEvent error(String errorMessage) {
        return StreamEvent.builder()
                .type(EventType.ERROR)
                .message(errorMessage)
                .build();
    }

    public static StreamEvent error(String errorCode, String errorMessage) {
        return StreamEvent.builder()
                .type(EventType.ERROR)
                .message(errorMessage)
                .data(Map.of("code", errorCode))
                .build();
    }

    public static StreamEvent skillStage(String stageName, String status, Object detail, long elapsedMs, int totalStages) {
        return StreamEvent.builder()
                .type(EventType.SKILL_STAGE)
                .message(stageName + " — " + status)
                .data(Map.of(
                        "stageName", stageName,
                        "status", status,
                        "detail", detail,
                        "elapsedMs", elapsedMs,
                        "totalStages", totalStages
                ))
                .build();
    }
}
