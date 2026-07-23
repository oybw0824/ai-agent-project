package com.nbcb.nacosmcpagent.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.audit")
public class AgentAuditProperties {

    /**
     * 是否启用模型与工具调用审计。
     */
    private boolean enabled = true;

    /**
     * 是否输出结构化应用日志。
     */
    private boolean logEnabled = true;

    /**
     * 是否写入数据库审计表。
     */
    private boolean dbEnabled = true;

    private boolean mcpToolEnabled = true;

    private boolean mcpToolDbEnabled = true;
}
