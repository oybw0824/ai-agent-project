package com.nbcb.agent.governance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具开关治理配置属性
 * <p>
 * 通过 @ConfigurationProperties 绑定 application.yml 中的 tool-governance 配置
 * 管理工具的启用/禁用状态
 */
@Configuration
@ConfigurationProperties(prefix = "tool-governance")
@Data
public class ToolGovernanceProperties {

    /**
     * 工具状态配置映射
     * key: 工具名称
     * value: 工具策略配置
     */
    private Map<String, ToolPolicy> tools = new HashMap<>();

    /**
     * 工具策略配置类
     */
    @Data
    public static class ToolPolicy {
        /**
         * 工具状态，默认为 DISABLED（未启用）
         * 避免遗漏配置导致误放行
         */
        private ToolStatus status = ToolStatus.DISABLED;
    }

    /**
     * 工具状态枚举
     */
    public enum ToolStatus {
        /**
         * 正常放行调用
         */
        ENABLED,

        /**
         * 模型不可见该工具；若仍被调用，直接拒绝，不执行实际逻辑
         */
        DISABLED
    }

    /**
     * 获取工具状态
     *
     * @param toolName 工具名称
     * @return 工具状态，未配置时返回 DISABLED
     */
    public ToolStatus getToolStatus(String toolName) {
        ToolPolicy policy = tools.get(toolName);
        return policy != null ? policy.getStatus() : ToolStatus.DISABLED;
    }
}