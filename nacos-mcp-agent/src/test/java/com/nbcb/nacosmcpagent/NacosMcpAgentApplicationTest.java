package com.nbcb.nacosmcpagent;

import io.modelcontextprotocol.server.McpServerFeatures;
import com.nbcb.nacosmcpagent.repository.AgentDefinitionRepository;
import com.nbcb.nacosmcpagent.agent.AgentRuntimeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agent.runtime.enabled=false",
                "spring.ai.model.chat=none",
                "spring.ai.alibaba.mcp.nacos.register.enabled=false",
                "spring.ai.alibaba.mcp.nacos.client.enabled=false",
                "spring.ai.mcp.client.enabled=false",
                "spring.autoconfigure.exclude="
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpToolCallbackAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpStreamableClientAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpSseClientAutoConfiguration"
        })
class NacosMcpAgentApplicationTest {

    @Autowired
    private List<McpServerFeatures.AsyncToolSpecification> localMcpTools;

    @Autowired
    private AgentDefinitionRepository agentDefinitionRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExportLocalWeatherAndCreditTools() {
        assertThat(localMcpTools)
                .extracting(specification -> specification.tool().name())
                .containsExactlyInAnyOrder(
                        "getWeatherByCity",
                        "queryEnterpriseCredit");
    }

    @Test
    void shouldAssemblePublishedAgentFromSevenPublicTables() {
        assertThat(agentDefinitionRepository.findPublishedAgents())
                .singleElement()
                .satisfies(definition -> {
                    assertThat(definition.agentId())
                            .isEqualTo("credit-agent");
                    assertThat(definition.nodes())
                            .hasSize(1);
                    assertThat(definition.nodes().get(0).model().modelId())
                            .isEqualTo("deepseek-main");
                    assertThat(definition.nodes().get(0).skills())
                            .extracting(skill -> skill.skillCode())
                            .containsExactlyInAnyOrder(
                                    "enterprise-credit-query",
                                    "local-weather-query");
                    assertThat(definition.nodes().get(0).skills())
                            .flatExtracting(skill -> skill.tools())
                            .extracting(tool -> tool.toolCode())
                            .containsExactlyInAnyOrder(
                                    "queryEnterpriseCredit",
                                    "getWeatherByCity");
                    assertThat(definition.nodes().get(0).agentTools())
                            .extracting(tool -> tool.toolCode())
                            .containsExactly("getWeatherByCity");
                });
    }

    @Test
    void shouldNotCreateAgentRuntimeWhenAgentRuntimeIsDisabled() {
        assertThat(applicationContext.getBeansOfType(
                AgentRuntimeRegistry.class)).isEmpty();
    }

    @Test
    void shouldInitializeAuditTable() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_CALL_AUDIT_LOG",
                Long.class);

        assertThat(count).isZero();
    }

    @Test
    void shouldInitializeMcpToolCallLogTable() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_MCP_TOOL_CALL_LOG",
                Long.class);

        assertThat(count).isZero();
    }
}
