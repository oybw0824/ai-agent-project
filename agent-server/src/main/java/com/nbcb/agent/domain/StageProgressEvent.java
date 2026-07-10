package com.nbcb.agent.domain;

/**
 * ★ Graph 阶段进度事件 — 解耦进度上报与 Graph 节点
 * <p>
 * 替代原先通过静态方法 SkillGenerationProgress.get() 的紧耦合方式。
 * 监听方（如 SSE 推送、SkillGenerationProgress）通过 Spring Event 机制异步接收。
 *
 * @author com.nbcb
 */
public class StageProgressEvent {

    public enum Status {
        START,
        COMPLETE
    }

    private final String progressId;
    private final String stageName;
    private final Status status;
    private final String detail;

    public StageProgressEvent(String progressId, String stageName, Status status, String detail) {
        this.progressId = progressId;
        this.stageName = stageName;
        this.status = status;
        this.detail = detail;
    }

    public String getProgressId() { return progressId; }
    public String getStageName() { return stageName; }
    public Status getStatus() { return status; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return "StageProgressEvent{progressId='" + progressId + "', stage='" + stageName
                + "', status=" + status + ", detail='" + detail + "'}";
    }
}
