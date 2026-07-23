package com.nbcb.agent.controller;

import com.nbcb.agent.domain.SkillGenerationRequest;
import com.nbcb.agent.domain.SkillGenerationResponse;
import com.nbcb.agent.domain.StageProgressEvent;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.service.SkillGenerationGraphService;
import com.nbcb.agent.service.McpCatalogService;
import com.nbcb.agent.util.SsePushHelper;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skill Generation API — 同步 + SSE 流式
 *
 * @author com.nbcb
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class SkillGenerationController {

    private final SkillGenerationGraphService skillGenerationGraphService;
    private final McpCatalogService mcpCatalogService;
    private final ThreadPoolTaskExecutor genExecutor;

    /** SSE 进度追踪：progressId → 进度快照 */
    private final ConcurrentHashMap<String, ProgressSnapshot> progressStore = new ConcurrentHashMap<>();

    /** SSE 连接索引：事件监听器可直接推送进度，无需占用线程轮询。 */
    private final ConcurrentHashMap<String, SseEmitter> emitterStore = new ConcurrentHashMap<>();

    public SkillGenerationController(SkillGenerationGraphService skillGenerationGraphService,
                                     McpCatalogService mcpCatalogService,
                                     @Qualifier("skillGenTaskExecutor") ThreadPoolTaskExecutor genExecutor) {
        this.skillGenerationGraphService = skillGenerationGraphService;
        this.mcpCatalogService = mcpCatalogService;
        this.genExecutor = genExecutor;
    }

    /** 监听 StageProgressEvent，更新进度快照 */
    @EventListener
    public void onStageProgress(StageProgressEvent event) {
        ProgressSnapshot snap = progressStore.computeIfAbsent(
                event.getProgressId(), k -> new ProgressSnapshot());
        snap.stages.put(event.getStageName(), Map.of(
                "status", event.getStatus().name().toLowerCase(),
                "detail", event.getDetail() != null ? event.getDetail() : ""));
        snap.lastUpdate = System.currentTimeMillis();
        SseEmitter emitter = emitterStore.get(event.getProgressId());
        if (emitter != null) {
            SsePushHelper.pushSilent(emitter, StreamEvent.skillStage(
                    event.getStageName(), event.getStatus().name().toLowerCase(),
                    event.getDetail() != null ? event.getDetail() : "",
                    0, 3));
        }
    }

    @PostMapping("/skill/generate")
    public SkillGenerationResponse generate(@Valid @RequestBody SkillGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("★ Skill Generation — PRD={}字符", request.getPrdContent().length());

        request.setMcpCatalog(mcpCatalogService.buildCatalogJson());
        try {
            SkillGenerationResponse response = skillGenerationGraphService.generate(request);
            return response;
        } catch (Exception e) {
            throw e;
        }
    }

    @PostMapping(value = "/skill/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@Valid @RequestBody SkillGenerationRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("★ Skill Generation (SSE) — PRD={}字符", request.getPrdContent().length());

        request.setMcpCatalog(mcpCatalogService.buildCatalogJson());
        String progressId = UUID.randomUUID().toString().substring(0, 8);
        progressStore.put(progressId, new ProgressSnapshot());

        SseEmitter emitter = new SseEmitter(600_000L);
        emitterStore.put(progressId, emitter);
        Runnable cleanup = () -> {
            emitterStore.remove(progressId);
            progressStore.remove(progressId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        AtomicReference<SkillGenerationResponse> resultRef = new AtomicReference<>();

        // 生成线程
        CompletableFuture<Void> genFuture = CompletableFuture.runAsync(() -> {
            try {
                SkillGenerationResponse response = skillGenerationGraphService
                        .generateWithProgress(request, progressId);
                resultRef.set(response);
            } catch (Exception e) {
                log.error("★ Skill Generation (SSE) 失败", e);
                SsePushHelper.pushSilent(emitter, StreamEvent.error("生成失败: " + e.getMessage()));
            }
        }, genExecutor);

        genFuture.orTimeout(600, TimeUnit.SECONDS)
                .whenComplete((unused, ex) -> {
                    try {
                        if (ex != null) {
                            SsePushHelper.pushSilent(emitter, StreamEvent.error("生成超时或中断: " + ex.getMessage()));
                        }
                        SkillGenerationResponse result = resultRef.get();
                        if (result != null) {
                            Map<String, Object> meta = Map.of(
                                    "valid", result.isValid(),
                                    "processingTimeMs", result.getProcessingTimeMs(),
                                    "skillMarkdown", result.getSkillMarkdown() != null
                                            ? result.getSkillMarkdown() : "",
                                    "validationErrors", result.getValidationErrors() != null
                                            ? result.getValidationErrors() : List.of()
                            );
                            emitter.send(SseEmitter.event().name("done")
                                    .data(StreamEvent.done(meta).toJson()));
                        }
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    } finally {
                        cleanup.run();
                    }
                });

        return emitter;
    }

    /** 进度快照 */
    private static class ProgressSnapshot {
        final Map<String, Map<String, String>> stages = new ConcurrentHashMap<>();
        volatile long lastUpdate;
    }
}
