package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("AI_TOOL")
public class AiToolEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String toolId;
    private String mcpCode;
    private String toolCode;
    private String toolName;
    private String isDelete;
    private String inputId;
    private String inputTime;
    private String inputOrg;
    private String updateId;
    private String updateTime;
    private String updateOrg;
}
