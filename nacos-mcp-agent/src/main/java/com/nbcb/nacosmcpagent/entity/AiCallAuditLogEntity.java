package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("AI_CALL_AUDIT_LOG")
public class AiCallAuditLogEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String traceId;
    private String spanId;
    private String callType;
    private String agentId;
    private String nodeId;
    private String callName;
    private String inputText;
    private String outputText;
    private String success;
    private String errorMessage;
    private Long durationMs;
    private String createTime;
}
