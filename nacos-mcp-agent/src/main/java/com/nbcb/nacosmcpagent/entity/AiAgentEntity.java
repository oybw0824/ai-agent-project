package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("AI_AGENT")
public class AiAgentEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String agentId;
    private String agentName;
    private String status;
    private String isDelete;
    private String inputId;
    private String inputTime;
    private String inputOrg;
    private String updateId;
    private String updateTime;
    private String updateOrg;
}
