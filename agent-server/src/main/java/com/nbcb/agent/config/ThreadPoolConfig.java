package com.nbcb.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * ★ 统一线程池配置（替代 AgentStreamService、SkillGenerationController、SingleStepGenerationService 各自 new 线程池）
 * <p>
 * 好处：
 * - 统一调优与生命周期管理
 * - Spring 自动管理生命周期（shutdown）
 * - application.yml 可配置
 *
 * @author com.nbcb
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Value("${agent.thread-pool.sse.core-size:4}")
    private int sseCoreSize;

    @Value("${agent.thread-pool.sse.max-size:16}")
    private int sseMaxSize;

    @Value("${agent.thread-pool.sse.queue-capacity:64}")
    private int sseQueueCapacity;

    @Value("${agent.thread-pool.skill-gen.core-size:2}")
    private int skillGenCoreSize;

    @Value("${agent.thread-pool.skill-gen.max-size:8}")
    private int skillGenMaxSize;

    @Value("${agent.thread-pool.skill-gen.queue-capacity:32}")
    private int skillGenQueueCapacity;

    // ==================== SSE 推送线程池 ====================

    @Bean(name = "sseTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
        return createExecutor("sse-agent-", sseCoreSize, sseMaxSize, sseQueueCapacity);
    }

    // ==================== Skill 生成线程池 ====================

    @Bean(name = "skillGenTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor skillGenTaskExecutor() {
        return createExecutor("skill-gen-", skillGenCoreSize, skillGenMaxSize, skillGenQueueCapacity);
    }

    // ==================== 私有 ====================

    private ThreadPoolTaskExecutor createExecutor(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setDaemon(true);
        executor.initialize();
        log.info("★ 创建线程池: prefix={}, core={}, max={}, queue={}", prefix, core, max, queue);
        return executor;
    }
}
