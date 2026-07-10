package com.nbcb.agent.util;

import com.nbcb.agent.domain.StreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * ★ SSE 事件推送工具类（消除 AgentStreamService、ToolEventInterceptor、SkillRegistry 三处重复代码）
 * <p>
 * 所有 SSE 事件推送统一通过此类，避免 send() + event.name() + event.data() 模式在多处重复。
 *
 * @author com.nbcb
 */
@Slf4j
public final class SsePushHelper {

    private SsePushHelper() {}

    /**
     * ★ 推送 SSE 事件（安全推送，失败仅 debug log）
     */
    public static void push(SseEmitter emitter, StreamEvent event) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(event.toJson()));
        } catch (IOException e) {
            log.debug("SSE 推送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * ★ 推送 SSE 事件（静默版本，不记录任何日志）
     */
    public static void pushSilent(SseEmitter emitter, StreamEvent event) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(event.toJson()));
        } catch (IOException ignored) {
            // 静默忽略
        }
    }
}
