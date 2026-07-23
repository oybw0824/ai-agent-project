package com.nbcb.nacosmcpagent.config;

import com.nbcb.nacosmcpagent.agent.AgentRuntimeRegistry;
import com.nbcb.nacosmcpagent.agent.DatabaseChatModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.alibaba.mcp.nacos.register.enabled=false",
                "spring.ai.alibaba.mcp.nacos.client.enabled=false",
                "spring.ai.mcp.client.enabled=false",
                "spring.ai.mcp.server.enabled=false",
                "spring.autoconfigure.exclude="
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpToolCallbackAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpStreamableClientAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpSseClientAutoConfiguration"
        })
class DatabaseAgentConfigurationTest {

    @Autowired
    private AgentRuntimeRegistry agentRuntimeRegistry;

    @Autowired
    private DatabaseChatModelFactory chatModelFactory;

    @Test
    void shouldAutoConfigureDatabaseAgentRuntime() {
        assertThat(agentRuntimeRegistry).isNotNull();
        assertThat(chatModelFactory).isNotNull();
        assertThat(agentRuntimeRegistry.listAgents())
                .extracting(runtime -> runtime.agentId())
                .containsExactly("credit-agent");
    }
}
