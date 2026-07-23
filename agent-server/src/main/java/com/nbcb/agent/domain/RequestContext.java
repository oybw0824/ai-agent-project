package com.nbcb.agent.domain;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li>{@link #REGISTRY} ConcurrentHashMap&lt;UUID, Context&gt; — 仅在唯一活动请求时跨线程降级</li>
 * </ol>
 *
 * @author com.nbcb
 */
public class RequestContext implements AutoCloseable {

    // ==================== 静态存储 ====================

    /** ★ 同线程上下文（sseExecutor 线程 → doOnNext/doOnComplete 回调） */
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

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
     * ★ 开始新请求，生成唯一 requestId 并注册到上下文存储
     *
     * @param emitter SSE 发射器（非流式时为 null）
     * @return RequestContext 实例
     */
    public static RequestContext begin(SseEmitter emitter) {
        String requestId = UUID.randomUUID().toString();
        RequestContext ctx = new RequestContext(requestId, emitter);
        CURRENT.set(ctx);
        REGISTRY.put(requestId, ctx);
        return ctx;
    }

    /**
     * ★ 获取当前请求的上下文（ThreadLocal → 唯一活动请求降级）
     * <p>
     * 并发请求下如果调用线程没有显式上下文，则返回 null。宁可少记录一次事件，
     * 也不能把工具轨迹或 SSE 数据写入其他用户的请求。
     *
     * @return 当前请求的 RequestContext，可能为 null
     */
    public static RequestContext current() {
        // 第一层：同线程 ThreadLocal（sseExecutor 线程）
        RequestContext ctx = CURRENT.get();
        if (ctx != null) return ctx;

        // 只有一个活动请求时，跨线程归属才是无歧义的
        if (REGISTRY.size() == 1) {
            return REGISTRY.values().iterator().next();
        }
        return null;
    }

    /**
     * ★ 清理所有静态存储（应用关闭时调用，防止内存泄漏）
     */
    public static void cleanupAll() {
        CURRENT.remove();
        REGISTRY.clear();
    }

    /**
     * 解除当前工作线程绑定，但保留注册表条目供异步回调使用。
     * 非阻塞 subscribe 返回后必须调用，避免线程池线程残留上一次请求。
     */
    public void detachCurrentThread() {
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }

    /** 获取当前请求的唯一标识。 */
    public String getRequestId() {
        return requestId;
    }

    /** 获取 SSE 发射器，非流式请求返回 null。 */
    public SseEmitter getEmitter() {
        return emitter;
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


    // ==================== 清理 ====================

    /**
     * ★ 清理当前请求的上下文存储
     * <p>
     * 使用 requestId 精确删除 REGISTRY 条目，CAS 清除 FALLBACK，
     * 确保并发请求的上下文不会被误清理。
     */
    @Override
    public void close() {
        REGISTRY.remove(requestId);
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }
}
