package com.nbcb.agent.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ★ 统一治理配置实体 — 通过 configType 区分四种配置类型
 * <p>
 * 单表替代原本的 agent_config / agent_user_whitelist / agent_org_whitelist / agent_route_config 四表。
 *
 * @author com.nbcb
 */
@Data
@TableName("agent_governance_config")
public class AgentGovernanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置类型：AGENT | USER | ORG | ROUTE */
    private String configType;

    // ==================== AGENT 类型专用字段 ====================

    /** Agent 名称（AGENT/USER/ORG 类型） */
    private String agentName;

    /** 状态：ENABLED | DISABLED（AGENT/USER/ORG 类型） */
    private String status;

    /** 默认状态（仅 AGENT 类型，兜底值） */
    private String defaultStatus;

    // ==================== USER/ORG 类型专用字段 ====================

    /** 用户 ID（仅 USER 类型） */
    private String userId;

    /** 机构 ID（仅 ORG 类型） */
    private String orgId;

    /** 生效开始时间（USER/ORG 类型，NULL 表示立即生效） */
    @TableField("effective_from")
    private LocalDateTime effectiveFrom;

    /** 生效结束时间（USER/ORG 类型，NULL 表示永久有效） */
    @TableField("effective_to")
    private LocalDateTime effectiveTo;

    // ==================== ROUTE 类型专用字段 ====================

    /** Ant 风格路径模式（仅 ROUTE 类型） */
    private String pathPattern;

    /** 是否流式接口（仅 ROUTE 类型，Oracle NUMBER(1) → Boolean） */
    private Boolean isStream;

    // ==================== 通用字段 ====================

    /** 描述信息 */
    private String description;

    // ==================== 类型常量 ====================

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_USER  = "USER";
    public static final String TYPE_ORG   = "ORG";
    public static final String TYPE_ROUTE = "ROUTE";
}
