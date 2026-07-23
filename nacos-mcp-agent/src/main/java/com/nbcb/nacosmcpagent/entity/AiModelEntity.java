package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("AI_MODEL")
public class AiModelEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String modelId;
    private String status;
    private String providerCode;
    private String modelName;
    private String endpointUri;
    private String credentialRef;
    private String isDelete;
    private String inputId;
    private String inputTime;
    private String inputOrg;
    private String updateId;
    private String updateTime;
    private String updateOrg;
}
