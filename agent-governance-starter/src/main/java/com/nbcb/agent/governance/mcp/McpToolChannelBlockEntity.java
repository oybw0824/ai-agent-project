package com.nbcb.agent.governance.mcp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * MCP 工具按渠道禁用配置。
 */
@Data
@TableName("AI_MCP_TOOL_CHANNEL_BLOCK")
public class McpToolChannelBlockEntity {

    @TableId(value = "PK_ID", type = IdType.INPUT)
    private String pkId;

    @TableField("MCP_SERVER_NAME")
    private String mcpServerName;

    @TableField("TOOL_NAME")
    private String toolName;

    @TableField("CHANNEL_CODE")
    private String channelCode;

    @TableField("STATUS")
    private String status;

    @TableField("MESSAGE")
    private String message;

    @TableField("IS_DELETE")
    private String isDelete;

    @TableField("EFFECTIVE_FROM")
    private String effectiveFrom;

    @TableField("EFFECTIVE_TO")
    private String effectiveTo;
}
