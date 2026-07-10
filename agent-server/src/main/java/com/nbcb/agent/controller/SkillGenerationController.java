package com.nbcb.agent.controller;

import com.nbcb.agent.domain.SkillGenerationRequest;
import com.nbcb.agent.domain.SkillGenerationResponse;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.SkillGenerationGraphService;
import com.nbcb.agent.domain.SkillGenerationProgress;
import com.nbcb.agent.service.McpCatalogService;
import com.nbcb.agent.util.SsePushHelper;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
@RequestMapping("/api/v1")
public class SkillGenerationController {

    private final SkillGenerationGraphService skillGenerationGraphService;
    private final McpCatalogService mcpCatalogService;
    private final AgentMetrics metrics;
    /** ★ 使用 Spring 统一管理的线程池 */
    private final ThreadPoolTaskExecutor genExecutor;

    public SkillGenerationController(SkillGenerationGraphService skillGenerationGraphService,
                                     McpCatalogService mcpCatalogService,
                                     AgentMetrics metrics,
                                     @Qualifier("skillGenTaskExecutor") ThreadPoolTaskExecutor genExecutor) {
        this.skillGenerationGraphService = skillGenerationGraphService;
        this.mcpCatalogService = mcpCatalogService;
        this.metrics = metrics;
        this.genExecutor = genExecutor;
    }

    @PostConstruct
    public void init() {
        metrics.registerThreadPool("skill-gen", genExecutor.getThreadPoolExecutor());
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
     * <p>
     * 使用 {@link CompletableFuture} 进行并发控制，复用项目现有的并发模式。
     */
    @PostMapping(value = "/skill/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@Valid @RequestBody SkillGenerationRequest request) {
        metrics.skillGenTotal.increment();
        long startTime = System.currentTimeMillis();
        log.info("★ 收到 Skill Generation (SSE) 请求 — PRD 长度={} 字符", request.getPrdContent().length());

        String mcpCatalog = mcpCatalogService.buildCatalogJson();
        request.setMcpCatalog(mcpCatalog);

        String progressId = UUID.randomUUID().toString().substring(0, 8);
        SkillGenerationProgress progress = SkillGenerationProgress.create(progressId);
        SseEmitter emitter = new SseEmitter(600_000L);

        // ★ 使用 CompletableFuture 简化并发控制（复用项目现有模式）
        CompletableFuture<Void> generationFuture = CompletableFuture.runAsync(() -> {
            try {
                SkillGenerationResponse response = skillGenerationGraphService.generateWithProgress(request, progressId);
                progress.complete(response);
                metrics.skillGenSuccess.increment();
                metrics.skillGenDuration.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("★ Skill Generation (SSE) 失败", e);
                metrics.skillGenFailure.increment();
                progress.fail(e.getMessage());
                SsePushHelper.pushSilent(emitter, StreamEvent.error("生成失败: " + e.getMessage()));
                emitter.completeWithError(e);
            }
        }, genExecutor);

        // 轮询线程：每 500ms 推送进度快照，直到 status 不再是 RUNNING
        CompletableFuture<Void> pollingFuture = CompletableFuture.runAsync(() -> {
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
            } catch (Exception e) {
                log.error("★ 轮询线程异常", e);
            }
        }, genExecutor);

        // ★ 生成完成 + 轮询完成后，发送 done 事件并清理
        CompletableFuture.allOf(generationFuture, pollingFuture)
                .orTimeout(700, TimeUnit.SECONDS)
                .whenComplete((unused, ex) -> {
                    try {
                        if (ex == null) {
                            SkillGenerationResponse result = progress.getResult();
                            Map<String, Object> doneMeta = buildDoneMetadata(result);
                            emitter.send(SseEmitter.event().name("done").data(StreamEvent.done(doneMeta).toJson()));
                            emitter.complete();
                        } else {
                            log.error("★ SSE 流程异常", ex);
                            emitter.completeWithError(ex);
                        }
                    } catch (IOException e) {
                        log.debug("SSE done 事件发送失败: {}", e.getMessage());
                        emitter.completeWithError(e);
                    } finally {
                        SkillGenerationProgress.remove(progressId);
                    }
                });

        return emitter;
    }

    private void sendProgressSnapshotSafely(SseEmitter emitter, SkillGenerationProgress progress) {
        if (progress == null) return;
        sendProgressSnapshot(emitter, progress);
    }

    /**
     * 构建完成事件的元数据
     * <p>
     * 使用 {@link ResponseBuilder} 提供统一格式。
     *
     * @param result 生成结果
     * @return 元数据 Map
     */
    private Map<String, Object> buildDoneMetadata(SkillGenerationResponse result) {
        if (result == null) {
            return Map.of();
        }
        return Map.of(
                "valid", result.isValid(),
                "processingTimeMs", result.getProcessingTimeMs(),
                "skillMarkdown", result.getSkillMarkdown() != null ? result.getSkillMarkdown() : "",
                "validationErrors", result.getValidationErrors() != null ? result.getValidationErrors() : List.of()
        );
    }

    private void sendProgressSnapshot(SseEmitter emitter, SkillGenerationProgress progress) {
        SsePushHelper.pushSilent(emitter, StreamEvent.skillStage(
                "progress",
                progress.getStatus().name().toLowerCase(),
                progress.toSnapshot(),
                progress.getElapsedMs(),
                4
        ));
    }

}