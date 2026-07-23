package com.nbcb.agent.governance.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 工具渠道治理组件配置。
 */
@Data
@ConfigurationProperties(prefix = "agent-governance.mcp-tool-channel")
public class McpToolChannelGovernanceProperties {

    private boolean enabled = true;

    private String channelHeader = McpToolChannelContext.CHANNEL_HEADER;

    private String unavailableMessage = "工具不可用";

    private int cacheTtlSeconds = 30;

    private int cacheMaxSize = 2000;
}
