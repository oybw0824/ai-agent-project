package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("AI_AGENT_NODE_SKILL_BINDING")
public class AiAgentNodeSkillBindingEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String nodeId;
    private String skillId;
    private String isDelete;
    private String inputId;
    private String inputTime;
    private String inputOrg;
    private String updateId;
    private String updateTime;
    private String updateOrg;
}
