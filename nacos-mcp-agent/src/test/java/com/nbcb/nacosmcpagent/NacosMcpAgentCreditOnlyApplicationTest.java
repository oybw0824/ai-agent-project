package com.nbcb.nacosmcpagent;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agent.runtime.enabled=false",
                "spring.ai.model.chat=none",
                "mcp.tools.weather.enabled=false",
                "mcp.tools.credit.enabled=true",
                "spring.ai.alibaba.mcp.nacos.register.enabled=false",
                "spring.ai.alibaba.mcp.nacos.client.enabled=false",
                "spring.ai.mcp.client.enabled=false",
                "spring.autoconfigure.exclude="
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpToolCallbackAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpStreamableClientAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpSseClientAutoConfiguration"
        })
class NacosMcpAgentCreditOnlyApplicationTest {

    @Autowired
    private List<McpServerFeatures.AsyncToolSpecification> localMcpTools;

    @Test
    void shouldExportOnlyEnterpriseCreditTool() {
        assertThat(localMcpTools)
                .extracting(specification -> specification.tool().name())
                .containsExactly("queryEnterpriseCredit");
    }
}
