package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("AI_AGENT_NODE")
public class AiAgentNodeEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String nodeId;
    private String agentId;
    private String nodeSystemPrompt;
    private String nodeUserPrompt;
    private String modelId;
    private BigDecimal temperature;
    private Integer maxToken;
    private String isDelete;
    private String inputId;
    private String inputTime;
    private String inputOrg;
    private String updateId;
    private String updateTime;
    private String updateOrg;
}
