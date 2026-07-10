package com.nbcb.agent.governance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 治理配置属性
 * <p>
 * 绑定 application.yml 中的 {@code agent-governance} 配置段，
 * 管理会话超时、模型调用次数、Token 预算、空转检测等治理参数。
 * <p>
 * ★ 设计原则：
 * <ul>
 *   <li>所有配置均有合理默认值，开箱即用</li>
 *   <li>支持按风险等级（default / high-risk）分级配置</li>
 *   <li>使用 Lombok {@code @Data} 自动生成 getter/setter</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent-governance")
public class AgentGovernanceProperties {

    /** 最大迭代次数配置 */
    private MaxIterations maxIterations = new MaxIterations();

    /** 会话超时预算配置 */
    private SessionTimeout sessionTimeout = new SessionTimeout();

    /** 模型调用次数限制配置 */
    private ModelCallLimit modelCallLimit = new ModelCallLimit();

    /** Token 预算配置 */
    private TokenBudget tokenBudget = new TokenBudget();

    /** 空转检测配置 */
    private LoopDetect loopDetect = new LoopDetect();

    // ==================== 内部配置类 ====================

    /**
     * 最大迭代次数配置
     */
    @Data
    public static class MaxIterations {
        /** 默认最大迭代次数 */
        private int defaultVal = 8;
        /** 高风险场景最大迭代次数 */
        private int l4HighRisk = 4;
    }

    /**
     * 会话超时预算配置
     */
    @Data
    public static class SessionTimeout {
        /** 默认会话超时预算（毫秒） */
        private long defaultBudgetMs = 15000;
        /** 高风险场景会话超时预算（毫秒） */
        private long highRiskBudgetMs = 10000;
        /** 最小下一步时间预算（毫秒），剩余时间不足时提前终止 */
        private long minimumNextStepMs = 1500;
    }

    /**
     * 模型调用次数限制配置
     */
    @Data
    public static class ModelCallLimit {
        /** 默认最大模型调用次数 */
        private int defaultLimit = 8;
        /** 高风险场景最大模型调用次数 */
        private int l4HighRiskLimit = 4;
    }

    /**
     * Token 预算配置
     */
    @Data
    public static class TokenBudget {
        /** 默认最大 Token 数 */
        private int defaultMaxTokens = 12000;
        /** 高风险场景最大 Token 数 */
        private int l4HighRiskMaxTokens = 8000;
        /** 警告比例（超过此比例时记录警告日志） */
        private double warningRatio = 0.8;
    }

    /**
     * 空转检测配置
     */
    @Data
    public static class LoopDetect {
        /** 连续重复调用阈值（同一工具+相同参数连续调用 N 次视为空转） */
        private int strictRepeatThreshold = 3;
        /** 滑动窗口大小（检测交替模式时使用的历史记录窗口） */
        private int patternWindow = 8;
        /** 交替重复阈值（A→B→A→B 交替 N 次视为空转） */
        private int alternatingRepeatThreshold = 3;
        /** 不可重试结果后仍调用同一工具时是否终止 */
        private boolean stopAfterNonRetryableResult = true;
    }
}
