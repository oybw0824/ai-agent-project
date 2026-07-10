package com.nbcb.agent.domain;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skill 生成进度管理器
 * <p>
 * 四阶段进度追踪：decomposePrd → resolveTools → generateSteps → assembleSkill
 * 各节点通过静态方法更新进度，Controller 通过 getCurrent() 读取推送 SSE。
 *
 * @author com.nbcb
 */
@Slf4j
public class SkillGenerationProgress {

    private static final ConcurrentHashMap<String, SkillGenerationProgress> REGISTRY = new ConcurrentHashMap<>();

    private final String id;
    private final long startTime;
    private final List<StageRecord> stages;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
    private volatile SkillGenerationResponse result;

    public enum Status { RUNNING, COMPLETED, FAILED }

    public enum StageStatus { PENDING, RUNNING, COMPLETED, FAILED }

    public static class StageRecord {
        public final String name;
        public final String label;
        public volatile StageStatus status;
        public volatile long startMs;
        public volatile long elapsedMs;
        public volatile String detail;

        StageRecord(String name, String label) {
            this.name = name;
            this.label = label;
            this.status = StageStatus.PENDING;
        }
    }

    private SkillGenerationProgress(String id) {
        this.id = id;
        this.startTime = System.currentTimeMillis();
        this.stages = List.of(
                new StageRecord("decomposePrd", "阶段一：PRD步骤拆解"),
                new StageRecord("resolveTools", "阶段二：MCP工具映射"),
                new StageRecord("generateSteps", "阶段三：单步生成"),
                new StageRecord("assembleSkill", "阶段四：组装与校验")
        );
    }

    public static SkillGenerationProgress create(String id) {
        SkillGenerationProgress p = new SkillGenerationProgress(id);
        REGISTRY.put(id, p);
        log.info("★ 进度追踪创建: id={}", id);
        return p;
    }

    public static SkillGenerationProgress get(String id) {
        return REGISTRY.get(id);
    }

    public static void remove(String id) {
        REGISTRY.remove(id);
    }

    public void stageStart(String stageName) {
        stages.stream()
                .filter(s -> s.name.equals(stageName))
                .findFirst()
                .ifPresent(s -> {
                    s.status = StageStatus.RUNNING;
                    s.startMs = System.currentTimeMillis();
                    s.elapsedMs = 0;
                    log.info("★ 阶段开始: {} — {}", stageName, s.label);
                });
    }

    public void stageComplete(String stageName, String detail) {
        stages.stream()
                .filter(s -> s.name.equals(stageName))
                .findFirst()
                .ifPresent(s -> {
                    s.status = StageStatus.COMPLETED;
                    s.elapsedMs = System.currentTimeMillis() - s.startMs;
                    s.detail = detail;
                    log.info("★ 阶段完成: {} — {}ms, detail={}", stageName, s.elapsedMs, detail);
                });
    }

    public void stageFailed(String stageName, String error) {
        stages.stream()
                .filter(s -> s.name.equals(stageName))
                .findFirst()
                .ifPresent(s -> {
                    s.status = StageStatus.FAILED;
                    s.elapsedMs = System.currentTimeMillis() - s.startMs;
                    s.detail = error;
                });
        this.status.set(Status.FAILED);
    }

    public void complete(SkillGenerationResponse result) {
        this.result = result;
        this.status.set(Status.COMPLETED);
    }

    public void fail(String error) {
        this.status.set(Status.FAILED);
    }

    public List<StageRecord> getStages() { return stages; }

    public Status getStatus() { return status.get(); }

    public SkillGenerationResponse getResult() { return result; }

    public long getElapsedMs() { return System.currentTimeMillis() - startTime; }

    public String getId() { return id; }

    public Map<String, Object> toSnapshot() {
        List<Object> stageList = new ArrayList<>();
        for (StageRecord s : stages) {
            stageList.add(Map.of(
                    "name", s.name,
                    "label", s.label,
                    "status", s.status.name().toLowerCase(),
                    "elapsedMs", s.elapsedMs,
                    "detail", s.detail != null ? s.detail : ""
            ));
        }
        return Map.of(
                "id", id,
                "overallStatus", status.get().name().toLowerCase(),
                "elapsedMs", getElapsedMs(),
                "stages", stageList
        );
    }
}