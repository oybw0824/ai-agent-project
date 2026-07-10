package com.nbcb.agent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.nbcb.agent.exception.LlmCallException;
import com.nbcb.agent.metric.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 统一调用模板 — 包装 ChatModel，提供超时 + 重试 + 缓存 + 指标
 * <p>
 * 设计原则：
 * <ol>
 *   <li>不替换 ChatModel Bean（ReactAgent 仍用原始 ChatModel，不受影响）</li>
 *   <li>Skill 生成四阶段改走 LlmCallTemplate 获得超时+重试+缓存能力</li>
 *   <li>同步 call() 缓存</li>
 *   <li>重试只针对连接/超时类异常，JSON 解析错误由 JsonRetryHelper 单独处理</li>
 * </ol>
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class LlmCallTemplate {

    private final ChatModel chatModel;
    private final Cache<String, String> cache;
    private final RetryTemplate retryTemplate;
    private final AgentMetrics metrics;
    private final long totalTimeoutMs;

    /** ★ 轻量熔断器：连续失败次数 + 熔断半开时间 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntil = new AtomicLong(0);

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;   // 连续失败 5 次熔断
    private static final long CIRCUIT_HALF_OPEN_MS = 30_000;   // 30s 后半开试探

    public LlmCallTemplate(ChatModel chatModel,
                            Cache<String, String> cache,
                            RetryTemplate retryTemplate,
                            AgentMetrics metrics,
                            @Value("${agent.llm.connect-timeout-ms:5000}") int connectTimeoutMs,
                            @Value("${agent.llm.read-timeout-ms:60000}") int readTimeoutMs) {
        this.chatModel = chatModel;
        this.cache = cache;
        this.retryTemplate = retryTemplate;
        this.metrics = metrics;
        this.totalTimeoutMs = connectTimeoutMs + readTimeoutMs;
        log.info("★ LlmCallTemplate 初始化 — connectTimeout={}ms, readTimeout={}ms, totalTimeout={}ms",
                connectTimeoutMs, readTimeoutMs, totalTimeoutMs);
    }

    // ==================== 同步调用（阶段一/二/四） ====================

    /**
     * 同步 LLM 调用（带缓存 + 超时 + 重试 + 指标）
     *
     * @param prompt    完整提示词
     * @param cacheKey  缓存 key（null 表示不缓存）
     * @param stageName 阶段名称（日志/指标用）
     * @return LLM 响应文本
     * @throws LlmCallException 超时或重试耗尽
     */
    public String call(String prompt, String cacheKey, String stageName) {
        long t0 = System.currentTimeMillis();

        // 1. 查缓存
        if (cacheKey != null) {
            String cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                metrics.skillGenCacheHit.increment();
                log.info("★ {} 缓存命中, cacheKey={}", stageName, cacheKey.substring(0, Math.min(20, cacheKey.length())));
                return cached;
            }
            metrics.skillGenCacheMiss.increment();
        }

        // 2. ★ 熔断检查：OPEN 状态快速失败
        if (isCircuitOpen()) {
            throw new LlmCallException(stageName + " LLM 调用被熔断（API 持续故障，请稍后重试）");
        }

        // 3. Retry + 超时
        String result;
        try {
            result = retryTemplate.execute(context -> {
                if (context.getRetryCount() > 0) {
                    metrics.skillGenRetry.increment();
                }
                return callWithTimeout(prompt, stageName);
            });
            // ★ 成功：重置熔断器
            onCallSuccess();
        } catch (Exception e) {
            // ★ 失败：记录到熔断器
            onCallFailure();
            throw new LlmCallException(stageName + " LLM 调用失败（重试耗尽）", e);
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("★ {} LLM 调用完成 — {}ms", stageName, elapsed);

        // 4. 缓存写入
        if (cacheKey != null && result != null && !result.isBlank()) {
            cache.put(cacheKey, result);
        }

        return result;
    }

    // ==================== ★ 熔断器 ====================

    private boolean isCircuitOpen() {
        int failures = consecutiveFailures.get();
        if (failures < CIRCUIT_BREAKER_THRESHOLD) {
            return false;
        }
        long now = System.currentTimeMillis();
        long openUntil = circuitOpenUntil.get();
        // HALF_OPEN: 超过半开时间，允许一次试探
        if (now >= openUntil) {
            log.warn("★ Circuit Breaker HALF_OPEN — 允许试探调用");
            return false;
        }
        log.warn("★ Circuit Breaker OPEN — 拒绝调用（连续失败 {} 次，{}ms 后重试）",
                failures, openUntil - now);
        metrics.skillGenCircuitOpen.increment();
        return true;
    }

    private void onCallSuccess() {
        consecutiveFailures.set(0);
    }

    private void onCallFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            circuitOpenUntil.set(System.currentTimeMillis() + CIRCUIT_HALF_OPEN_MS);
            log.error("★ Circuit Breaker OPEN — 连续 {} 次失败，熔断 {}ms",
                    failures, CIRCUIT_HALF_OPEN_MS);
        }
    }

    /**
     * ★ 单次 LLM 同步调用 — 真正的超时控制（非事后校验）
     * <p>
     * 使用 CompletableFuture.orTimeout 实现主动超时中断，
     * 而非之前的"先等调用完再比较耗时"的假超时。
     */
    private String callWithTimeout(String prompt, String stageName) {
        log.debug("{} callWithTimeout — using {}ms total timeout", stageName, totalTimeoutMs);

        try {
            String result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return chatModel.call(new Prompt(new UserMessage(prompt)))
                                    .getResult().getOutput().getText();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, ForkJoinPool.commonPool())
                    .orTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(e -> {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new LlmCallException(
                                stageName + " LLM 调用超时（" + totalTimeoutMs + "ms）", e);
                    })
                    .join();

            if (result == null || result.isBlank()) {
                throw new LlmCallException(stageName + " LLM 返回空响应");
            }

            return result;
        } catch (LlmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCallException(stageName + " LLM 调用异常: " + e.getMessage(), e);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 探测缓存是否命中（不触发 LLM 调用），供阶段层做缓存预检查以跳过 prompt 构建开销。
     * <p>
     * 命中时计入 cacheHit 指标并返回缓存值；未命中返回 null（不计指标，
     * 后续由 {@link #call} 统一计入 cacheMiss）。
     *
     * @param cacheKey 缓存 key（null 表示不缓存，直接返回 null）
     * @return 缓存的原始响应，或 null（未命中）
     */
    public String peekCache(String cacheKey) {
        if (cacheKey == null) {
            return null;
        }
        String cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            metrics.skillGenCacheHit.increment();
            log.info("★ 缓存预检查命中, cacheKey={}",
                    cacheKey.substring(0, Math.min(20, cacheKey.length())));
            return cached;
        }
        return null;
    }

    /**
     * 构建缓存 key：stage + ":" + sha256(content)
     */
    public static String buildCacheKey(String stage, String content) {
        return stage + ":" + sha256(content);
    }

    /**
     * SHA-256 哈希（用于缓存 key）
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}