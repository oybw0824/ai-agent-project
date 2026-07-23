package com.nbcb.nacosmcpagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("AI_MCP_TOOL_CALL_LOG")
public class AiMcpToolCallLogEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    private String traceId;
    private String spanId;
    private String mcpServerName;
    private String mcpEndpoint;
    private String toolName;
    private String toolInput;
    private String toolOutput;
    private String success;
    private String errorMessage;
    private Long durationMs;
    private String createTime;
}
