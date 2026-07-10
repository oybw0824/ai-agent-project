package com.nbcb.agent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Agent 早期终止异常
 * <p>
 * 当 Agent 治理 Hook 检测到限流、超时、空转等条件时抛出此异常，
 * 强制终止当前会话的模型推理循环。
 * <p>
 * ★ 终止原因枚举：
 * <ul>
 *   <li>{@code TIMEOUT} — 会话超时预算耗尽</li>
 *   <li>{@code MODEL_CALL_LIMIT} — 模型调用次数超过限制</li>
 *   <li>{@code TOKEN_BUDGET} — Token 消耗超过预算</li>
 *   <li>{@code LOOP_DETECT} — 检测到空转（重复/交替工具调用）</li>
 *   <li>{@code TOOL_UNAVAILABLE} — 工具不可用且不可重试</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Getter
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class AgentEarlyTerminationException extends RuntimeException {

    /** 终止原因码 */
    private final String reason;

    /** 会话标识 */
    private final String sessionId;

    /**
     * 构造 Agent 早期终止异常
     *
     * @param reason    终止原因码（如 TIMEOUT、LOOP_DETECT）
     * @param message   详细描述
     * @param sessionId 会话 ID
     */
    public AgentEarlyTerminationException(String reason, String message, String sessionId) {
        super(message);
        this.reason = reason;
        this.sessionId = sessionId;
    }

    /**
     * 构造 Agent 早期终止异常（无 sessionId）
     *
     * @param reason  终止原因码
     * @param message 详细描述
     */
    public AgentEarlyTerminationException(String reason, String message) {
        this(reason, message, null);
    }

    // ==================== 终止原因常量 ====================

    /** 会话超时预算耗尽 */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** 模型调用次数超限 */
    public static final String REASON_MODEL_CALL_LIMIT = "MODEL_CALL_LIMIT";

    /** Token 预算超限 */
    public static final String REASON_TOKEN_BUDGET = "TOKEN_BUDGET";

    /** 空转检测（重复工具调用） */
    public static final String REASON_LOOP_DETECT = "LOOP_DETECT";

    /** 工具不可用 */
    public static final String REASON_TOOL_UNAVAILABLE = "TOOL_UNAVAILABLE";
}
