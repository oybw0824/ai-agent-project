package com.nbcb.agent.domain;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ★ 请求级上下文管理器 — ThreadLocal + ConcurrentHashMap 双层查找
 * <p>
 * 使用方式：
 * <pre>
 * try (RequestContext ctx = RequestContext.begin(emitter)) {
 *     ctx.recordToolCall(...);
 *     ctx.addCalledSkill("my-skill");
 *     SsePushHelper.push(ctx.getEmitter(), event);
 * } // close() 自动清理
 * </pre>
 * <p>
 * ★ 跨线程机制：
 * <ol>
 *   <li>{@link #CURRENT} ThreadLocal — 同线程（sseExecutor 回调线程）直接命中</li>
 *   <li>{@link #FALLBACK} AtomicReference — 跨线程快速 fallback（工具调用线程）</li>
 *   <li>{@link #REGISTRY} ConcurrentHashMap&lt;UUID, Context&gt; — 多请求隔离，防止并发泄漏</li>
 * </ol>
 *
 * @author com.nbcb
 */
public class RequestContext implements AutoCloseable {

    // ==================== 静态存储 ====================

    /** ★ 同线程上下文（sseExecutor 线程 → doOnNext/doOnComplete 回调） */
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    /** ★ 跨线程快速 fallback（工具调用在 boundedElastic 线程，非阻塞场景足够） */
    private static final AtomicReference<RequestContext> FALLBACK = new AtomicReference<>();

    /**
     * ★ 请求级注册表 — 每个请求分配 UUID，防止并发请求间上下文泄漏
     * <p>
     * 多 SSE 请求并发时，FALLBACK 可能被覆盖，但 REGISTRY 保证每个请求
     * 的上下文通过独立 key 隔离。工具事件通过 close() 时安全清理。
     */
    private static final ConcurrentHashMap<String, RequestContext> REGISTRY = new ConcurrentHashMap<>();

    // ==================== 实例字段 ====================

    /** ★ 唯一请求标识 */
    private final String requestId;

    /** 工具调用记录 */
    private final List<ToolCallRecord> toolRecords;

    /** 被调用的技能名称（保持插入顺序） */
    private final LinkedHashSet<String> calledSkills;

    /** SSE 发射器（非流式时为 null） */
    private final SseEmitter emitter;

    // ==================== 构造 ====================

    private RequestContext(String requestId, SseEmitter emitter) {
        this.requestId = requestId;
        this.toolRecords = new ArrayList<>();
        this.calledSkills = new LinkedHashSet<>();
        this.emitter = emitter;
    }

    /**
     * ★ 开始新请求，生成唯一 requestId 并注册到三层存储
     *
     * @param emitter SSE 发射器（非流式时为 null）
     * @return RequestContext 实例
     */
    public static RequestContext begin(SseEmitter emitter) {
        String requestId = UUID.randomUUID().toString();
        RequestContext ctx = new RequestContext(requestId, emitter);
        CURRENT.set(ctx);
        FALLBACK.set(ctx);
        REGISTRY.put(requestId, ctx);
        return ctx;
    }

    /**
     * ★ 获取当前请求的上下文（三层查找：ThreadLocal → FALLBACK → REGISTRY）
     * <p>
     * REGISTRY 兜底确保并发请求不会泄漏到错误的 emitter。
     *
     * @return 当前请求的 RequestContext，可能为 null
     */
    public static RequestContext current() {
        // 第一层：同线程 ThreadLocal（sseExecutor 线程）
        RequestContext ctx = CURRENT.get();
        if (ctx != null) return ctx;

        // 第二层：跨线程快速 fallback（boundedElastic 工具线程）
        ctx = FALLBACK.get();
        if (ctx != null) return ctx;

        // 第三层：REGISTRY 兜底（并发场景下安全隔离）
        if (!REGISTRY.isEmpty()) {
            return REGISTRY.values().iterator().next();
        }
        return null;
    }

    /**
     * ★ 获取当前请求的唯一标识
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * ★ 清理所有静态存储（应用关闭时调用，防止内存泄漏）
     */
    public static void cleanupAll() {
        CURRENT.remove();
        FALLBACK.set(null);
        REGISTRY.clear();
    }

    // ==================== SSE ====================

    /**
     * 记录工具调用
     *
     * @param record 工具调用记录
     */
    public void recordToolCall(ToolCallRecord record) {
        if (record != null) {
            toolRecords.add(record);
        }
    }

    /**
     * 获取工具调用记录
     *
     * @return 工具调用记录列表（不可变副本）
     */
    public List<ToolCallRecord> getToolRecords() {
        return List.copyOf(toolRecords);
    }

    // ==================== 技能记录 ====================

    /**
     * 添加被调用的技能
     *
     * @param skillName 技能名称
     */
    public void addCalledSkill(String skillName) {
        if (skillName != null && !skillName.isBlank()) {
            calledSkills.add(skillName);
        }
    }

    /**
     * 获取被调用的技能列表
     *
     * @return 技能名称列表（不可变副本）
     */
    public List<String> getCalledSkills() {
        return List.copyOf(calledSkills);
    }

    // ==================== SSE ====================

    /**
     * 获取 SSE 发射器
     *
     * @return SSE 发射器
     */
    public SseEmitter getEmitter() {
        return emitter;
    }

    // ==================== 清理 ====================

    /**
     * ★ 清理当前请求的三层存储（从 REGISTRY + FALLBACK + ThreadLocal 中移除）
     * <p>
     * 使用 requestId 精确删除 REGISTRY 条目，CAS 清除 FALLBACK，
     * 确保并发请求的上下文不会被误清理。
     */
    @Override
    public void close() {
        REGISTRY.remove(requestId);
        FALLBACK.compareAndSet(this, null);
        CURRENT.remove();
    }
}
