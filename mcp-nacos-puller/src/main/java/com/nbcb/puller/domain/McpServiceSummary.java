package com.nbcb.puller.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServiceSummary {
    private String serviceName;
    private String groupName;
    private int instanceCount;
    private int healthyInstanceCount;
    private String mcpEndpoint;
    private String version;
    private String protocol;
}