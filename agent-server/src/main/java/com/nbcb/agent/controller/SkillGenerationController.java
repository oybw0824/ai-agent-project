package com.nbcb.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.domain.SkillGenerationRequest;
import com.nbcb.agent.domain.SkillGenerationResponse;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.SkillGenerationGraphService;
import com.nbcb.agent.service.SkillGenerationProgress;
import com.nbcb.agent.service.McpCatalogService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Skill Generation Agent 接口
 * <p>
 * 接收 PRD 需求文档，自动生成 Skill Markdown 文件。
 * MCP Tool Catalog 由 Controller 自动构建，无需调用方传入。
 *
 * @author com.nbcb
 */
@Slf4j
@RestController
public class SkillGenerationController {

    private final SkillGenerationGraphService skillGenerationGraphService;
    private final ObjectMapper objectMapper;
    private final McpCatalogService mcpCatalogService;
    private final AgentMetrics metrics;
    private final ThreadPoolExecutor genExecutor = new ThreadPoolExecutor(
            2,                                      // corePoolSize（Skill生成低频操作）
            8,                                      // maxPoolSize
            60L, TimeUnit.SECONDS,                  // keepAlive
            new LinkedBlockingQueue<>(32),          // bounded queue
            r -> {
                Thread t = new Thread(r, "skill-gen-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public SkillGenerationController(SkillGenerationGraphService skillGenerationGraphService,
                                     ObjectMapper objectMapper,
                                     McpCatalogService mcpCatalogService,
                                     AgentMetrics metrics) {
        this.skillGenerationGraphService = skillGenerationGraphService;
        this.objectMapper = objectMapper;
        this.mcpCatalogService = mcpCatalogService;
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        metrics.registerThreadPool("skill-gen", genExecutor);
    }

    /**
     * ★ 关闭线程池，防止资源泄漏
     */
    @PreDestroy
    public void destroy() {
        genExecutor.shutdown();
        try {
            if (!genExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                genExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            genExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 生成 Skill Markdown（四阶段 Graph 流程）
     * <p>
     * 四阶段 Pipeline：拆解 → 工具映射 → 单步生成 → 组装校验
     * 请求体只需传入 PRD 内容，MCP Catalog 由服务端自动构建。
     *
     * @param request 包含 PRD 内容和可选模板的请求
     * @return 生成的 Skill Markdown 及校验结果
     */
    @PostMapping("/skill/generate")
    public SkillGenerationResponse generate(@Valid @RequestBody SkillGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        metrics.skillGenTotal.increment();
        log.info("★ 收到 Skill Generation 请求 — PRD 长度={} 字符", request.getPrdContent().length());

        String mcpCatalog = mcpCatalogService.buildCatalogJson();
        request.setMcpCatalog(mcpCatalog);

        try {
            SkillGenerationResponse response = skillGenerationGraphService.generate(request);
            metrics.skillGenSuccess.increment();
            metrics.skillGenDuration.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);

            log.info("★ Skill Generation 响应 — 校验={}, 耗时={}ms",
                    response.isValid() ? "通过" : "失败", response.getProcessingTimeMs());
            return response;
        } catch (Exception e) {
            metrics.skillGenFailure.increment();
            throw e;
        }
    }

    /**
     * SSE 流式生成 Skill Markdown — 实时推送四阶段进度
     */
    @PostMapping(value = "/skill/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@Valid @RequestBody SkillGenerationRequest request) {
        log.info("★ 收到 Skill Generation (SSE) 请求 — PRD 长度={} 字符", request.getPrdContent().length());

        String mcpCatalog = mcpCatalogService.buildCatalogJson();
        request.setMcpCatalog(mcpCatalog);

        String progressId = UUID.randomUUID().toString().substring(0, 8);
        // ★ 先创建进度对象，再启动线程，消除轮询线程读 null 的竞态
        SkillGenerationProgress progress = SkillGenerationProgress.create(progressId);
        SseEmitter emitter = new SseEmitter(600_000L);
        CountDownLatch pollerDone = new CountDownLatch(1);

        // 生成线程：执行 Graph → 完成后等轮询线程收尾
        genExecutor.submit(() -> {
            try {
                SkillGenerationResponse response = skillGenerationGraphService.generateWithProgress(request, progressId);
                progress.complete(response);
            } catch (Exception e) {
                log.error("★ Skill Generation (SSE) 失败", e);
                progress.fail(e.getMessage());
                pollerDone.countDown();
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(StreamEvent.error("生成失败: " + e.getMessage()).toJson()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
                SkillGenerationProgress.remove(progressId);
                return;
            }

            // 等轮询线程发出最后一次 progress 快照
            try {
                pollerDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 发最终 done 事件 + markdown
            try {
                SkillGenerationResponse result = progress.getResult();
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(StreamEvent.done(result != null
                                ? Map.of(
                                        "valid", result.isValid(),
                                        "processingTimeMs", result.getProcessingTimeMs(),
                                        "skillMarkdown", result.getSkillMarkdown() != null ? result.getSkillMarkdown() : "",
                                        "validationErrors", result.getValidationErrors() != null ? result.getValidationErrors() : List.of()
                                )
                                : Map.of()).toJson()));
                emitter.complete();
            } catch (IOException e) {
                log.debug("SSE done 事件发送失败: {}", e.getMessage());
                emitter.completeWithError(e);
            } finally {
                SkillGenerationProgress.remove(progressId);
            }
        });

        // 轮询线程：每 500ms 推送进度快照，直到 status 不再是 RUNNING
        genExecutor.submit(() -> {
            try {
                SkillGenerationProgress.Status lastStatus = SkillGenerationProgress.Status.RUNNING;
                while (lastStatus == SkillGenerationProgress.Status.RUNNING) {
                    try {
                        Thread.sleep(500);
                        SkillGenerationProgress p = SkillGenerationProgress.get(progressId);
                        if (p == null) break;
                        lastStatus = p.getStatus();
                        if (lastStatus == SkillGenerationProgress.Status.RUNNING) {
                            sendProgressSnapshotSafely(emitter, p);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // 发送最终状态快照
                sendProgressSnapshotSafely(emitter, SkillGenerationProgress.get(progressId));
            } finally {
                pollerDone.countDown();
            }
        });

        return emitter;
    }

    private void sendProgressSnapshotSafely(SseEmitter emitter, SkillGenerationProgress progress) {
        if (progress == null) return;
        try {
            sendProgressSnapshot(emitter, progress);
        } catch (IOException e) {
            log.debug("SSE 进度推送失败（连接已关闭）: {}", e.getMessage());
        }
    }

    private void sendProgressSnapshot(SseEmitter emitter, SkillGenerationProgress progress) throws IOException {
        emitter.send(SseEmitter.event()
                .name("skill_stage")
                .data(StreamEvent.skillStage(
                        "progress",
                        progress.getStatus().name().toLowerCase(),
                        progress.toSnapshot(),
                        progress.getElapsedMs(),
                        4
                ).toJson()));
    }

    /**
     * 构建 MCP Tool Catalog JSON
     * <p>
     * 委托给 McpCatalogService，统一管理 MCP 工具元数据。
     */
    private String buildMcpCatalog() {
        return mcpCatalogService.buildCatalogJson();
    }
}