package com.nbcb.agent.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;

/**
 * LLM 调用基础设施配置 — Caffeine 缓存 + RetryTemplate
 * <p>
 * 版本由 Spring Boot 3.5.8 BOM 管理，无需显式声明。
 * <ul>
 *   <li>{@link #llmCache} — LLM 响应缓存（Caffeine，TTL 6h，max 500）</li>
 *   <li>{@link #llmRetryTemplate} — 编程式重试（指数退避，流式友好）</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@Configuration
public class LlmCallTemplateConfig {

    /**
     * LLM 响应缓存
     * <p>
     * key = stage + ":" + sha256(prompt)，PRD 改动 → prompt 变 → key 变 → 自然失效
     */
    @Bean
    public Cache<String, String> llmCache(
            @Value("${agent.llm.cache.maximum-size:500}") int maxSize,
            @Value("${agent.llm.cache.ttl-hours:6}") int ttlHours) {
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofHours(ttlHours))
                .recordStats()
                .build();
        log.info("★ LLM 响应缓存初始化 — maxSize={}, ttl={}h", maxSize, ttlHours);
        return cache;
    }

    /**
     * LLM 调用重试模板（编程式，流式友好）
     * <p>
     * 为什么不用 @Retryable：@Retryable 基于代理，对返回 Flux 的方法重试语义复杂。
     * 编程式 RetryTemplate 可精确控制：同步 call() 全程重试，流式 stream() 只重试连接建立。
     * <p>
     * 退避算法：指数退避 initial * multiplier^n，封顶 maxBackoff
     */
    @Bean
    public RetryTemplate llmRetryTemplate(
            @Value("${agent.llm.retry.max-attempts:3}") int maxAttempts,
            @Value("${agent.llm.retry.backoff-initial-ms:1000}") long initial,
            @Value("${agent.llm.retry.backoff-multiplier:2.0}") double multiplier,
            @Value("${agent.llm.retry.backoff-max-ms:8000}") long maxBackoff) {

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initial);
        backOffPolicy.setMultiplier(multiplier);
        backOffPolicy.setMaxInterval(maxBackoff);

        RetryTemplate template = RetryTemplate.builder()
                .customPolicy(retryPolicy)
                .customBackoff(backOffPolicy)
                .build();

        // 注册 RetryListener，重试时记日志（指标由 LlmCallTemplate 内部递增）
        template.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
                return true;
            }

            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback,
                                                         Throwable throwable) {
            }

            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
                                                          Throwable throwable) {
                log.warn("★ LLM 调用重试 {}/{}: {}", context.getRetryCount(), maxAttempts, throwable.getMessage());
            }
        });

        log.info("★ LLM RetryTemplate 初始化 — maxAttempts={}, backoff={}ms*{}(max {}ms)",
                maxAttempts, initial, multiplier, maxBackoff);
        return template;
    }
}
