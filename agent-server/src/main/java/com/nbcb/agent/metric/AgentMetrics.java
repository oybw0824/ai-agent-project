package com.nbcb.agent.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ Agent 统一监控指标注册中心
 * <p>
 * 所有业务指标通过 Micrometer 注册，自动暴露到 Prometheus。
 * 接入方调用 {@link #registerThreadPool(String, ThreadPoolExecutor)} 注册线程池指标。
 *
 * <pre>
 * 指标分类：
 * ┌─ agent.chat（对话）
 * │   ├─ counter 请求总数 / 成功 / 失败 / 重试
 * │   └─ timer  耗时分布
 * ├─ agent.sse（流式）
 * │   ├─ counter 连接建立 / 正常关闭 / 超时 / 异常
 * │   └─ timer  会话时长
 * ├─ agent.skill（技能生成）
 * │   ├─ counter 生成总数 / 成功 / 失败
 * │   └─ timer  各阶段耗时
 * ├─ agent.tool（工具）
 * │   └─ gauge  已注册工具数
 * ├─ agent.nacos（Nacos）
 * │   └─ gauge  连通性 / 已加载技能数
 * └─ agent.threadpool（线程池）
 *     └─ gauge  队列大小 / 活跃线程 / 已完成任务
 * </pre>
 *
 * @author nbcb
 */
@Slf4j
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    // ==================== Agent 对话指标 ====================

    /** 对话请求总数 */
    public final Counter chatTotal;

    /** 对话成功数 */
    public final Counter chatSuccess;

    /** 对话失败数 */
    public final Counter chatFailure;

    /** 对话重试次数 */
    public final Counter chatRetry;

    /** 对话耗时分布 */
    public final Timer chatDuration;

    // ==================== SSE 流式指标 ====================

    /** 流式连接建立数 */
    public final Counter sseConnections;

    /** 流式正常关闭数 */
    public final Counter sseCompleted;

    /** 流式超时关闭数 */
    public final Counter sseTimeout;

    /** 流式异常关闭数 */
    public final Counter sseError;

    /** 流式会话时长 */
    public final Timer sseDuration;

    // ==================== 技能生成指标 ====================

    /** 技能生成请求总数 */
    public final Counter skillGenTotal;

    /** 技能生成成功数 */
    public final Counter skillGenSuccess;

    /** 技能生成失败数 */
    public final Counter skillGenFailure;

    /** 技能生成耗时 */
    public final Timer skillGenDuration;

    /** 技能生成各阶段耗时 */
    public final Timer skillGenPhaseDecompose;
    public final Timer skillGenPhaseToolMap;
    public final Timer skillGenPhaseStepGen;
    public final Timer skillGenPhaseAssembly;

    // ==================== ★ v2 Skill 生成增强指标 ====================

    /** LLM 响应缓存命中数 */
    public final Counter skillGenCacheHit;

    /** LLM 响应缓存未命中数 */
    public final Counter skillGenCacheMiss;

    /** LLM 调用重试次数 */
    public final Counter skillGenRetry;

    /** ★ LLM 调用熔断次数 */
    public final Counter skillGenCircuitOpen;

    // ==================== Agent 治理指标 ====================

    /** 会话超时终止次数 */
    public final Counter governanceSessionTimeout;

    /** 模型调用次数超限终止次数 */
    public final Counter governanceModelCallLimit;

    /** Token 预算超限终止次数 */
    public final Counter governanceTokenBudget;

    /** 空转检测触发次数 */
    public final Counter governanceLoopDetect;

    // ==================== 工具指标 ====================

    /** 各阶段耗时 */
    public final Counter toolCallTotal;
    public final Counter toolCallSuccess;
    public final Counter toolCallFailure;

    // ==================== Nacos 指标 ====================

    /** Nacos 连通性（1=连通，0=断开） */
    public final AtomicInteger nacosConnected = new AtomicInteger(0);

    /** 已加载技能数 */
    public final AtomicInteger nacosLoadedSkillCount = new AtomicInteger(0);

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.chatTotal = buildCounter("agent.chat.total", "对话请求总数");
        this.chatSuccess = buildCounter("agent.chat.success", "对话成功数");
        this.chatFailure = buildCounter("agent.chat.failure", "对话失败数");
        this.chatRetry = buildCounter("agent.chat.retry", "对话重试次数");
        this.chatDuration = buildTimer("agent.chat.duration", "对话耗时");

        this.sseConnections = buildCounter("agent.sse.connections", "SSE 连接建立数");
        this.sseCompleted = buildCounter("agent.sse.completed", "SSE 正常关闭数");
        this.sseTimeout = buildCounter("agent.sse.timeout", "SSE 超时数");
        this.sseError = buildCounter("agent.sse.error", "SSE 异常数");
        this.sseDuration = buildTimer("agent.sse.duration", "SSE 会话时长");

        this.skillGenTotal = buildCounter("agent.skill.gen.total", "技能生成总数");
        this.skillGenSuccess = buildCounter("agent.skill.gen.success", "技能生成成功数");
        this.skillGenFailure = buildCounter("agent.skill.gen.failure", "技能生成失败数");
        this.skillGenDuration = buildTimer("agent.skill.gen.duration", "技能生成总耗时");
        this.skillGenPhaseDecompose = buildTimer("agent.skill.gen.phase.decompose", "阶段一拆解耗时");
        this.skillGenPhaseToolMap = buildTimer("agent.skill.gen.phase.toolmap", "阶段二工具映射耗时");
        this.skillGenPhaseStepGen = buildTimer("agent.skill.gen.phase.stepgen", "阶段三单步生成耗时");
        this.skillGenPhaseAssembly = buildTimer("agent.skill.gen.phase.assembly", "阶段四组装校验耗时");

        this.skillGenCacheHit = buildCounter("agent.skill.gen.cache.hit", "LLM 响应缓存命中数");
        this.skillGenCacheMiss = buildCounter("agent.skill.gen.cache.miss", "LLM 响应缓存未命中数");
        this.skillGenRetry = buildCounter("agent.skill.gen.retry", "LLM 调用重试次数");
        this.skillGenCircuitOpen = buildCounter("agent.skill.gen.circuit.open", "LLM 调用熔断次数");

        this.governanceSessionTimeout = buildCounter("agent.governance.session.timeout", "会话超时终止次数");
        this.governanceModelCallLimit = buildCounter("agent.governance.model.call.limit", "模型调用次数超限");
        this.governanceTokenBudget = buildCounter("agent.governance.token.budget", "Token 预算超限");
        this.governanceLoopDetect = buildCounter("agent.governance.loop.detect", "空转检测触发次数");
        this.toolCallTotal = buildCounter("agent.tool.call.total", "工具调用总数");
        this.toolCallSuccess = buildCounter("agent.tool.call.success", "工具调用成功数");
        this.toolCallFailure = buildCounter("agent.tool.call.failure", "工具调用失败数");
    }

    @PostConstruct
    public void init() {
        registerGauge("agent.nacos.connected", "Nacos 连通性", nacosConnected);
        registerGauge("agent.nacos.loaded.skills", "已加载技能数", nacosLoadedSkillCount);
        log.info("★ AgentMetrics 初始化完成 — {} 个指标已注册", registry.getMeters().size());
    }

    /**
     * ★ 注册线程池监控指标（Gauge 自动追踪）
     *
     * @param poolName 线程池名称（如 agent-chat、sse-agent、skill-gen）
     * @param executor 线程池实例
     */
    public void registerThreadPool(String poolName, ThreadPoolExecutor executor) {
        io.micrometer.core.instrument.Gauge.builder("agent.threadpool.queue.size", executor, e -> (double) e.getQueue().size())
                .tag("pool", poolName).register(registry);
        io.micrometer.core.instrument.Gauge.builder("agent.threadpool.active", executor, e -> (double) e.getActiveCount())
                .tag("pool", poolName).register(registry);
        io.micrometer.core.instrument.Gauge.builder("agent.threadpool.pool.size", executor, e -> (double) e.getPoolSize())
                .tag("pool", poolName).register(registry);
        io.micrometer.core.instrument.Gauge.builder("agent.threadpool.completed", executor, e -> (double) e.getCompletedTaskCount())
                .tag("pool", poolName).register(registry);
        io.micrometer.core.instrument.Gauge.builder("agent.threadpool.max", executor, e -> (double) e.getMaximumPoolSize())
                .tag("pool", poolName).register(registry);
        log.info("★ 线程池 [{}] 监控已注册", poolName);
    }

    /**
     * ★ 注册基于 AtomicInteger 的 Gauge（工具数、技能数等）
     */
    public void registerGauge(String name, String description, AtomicInteger value) {
        registry.gauge(name, value);
    }

    private Counter buildCounter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(registry);
    }

    private Timer buildTimer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .register(registry);
    }
}