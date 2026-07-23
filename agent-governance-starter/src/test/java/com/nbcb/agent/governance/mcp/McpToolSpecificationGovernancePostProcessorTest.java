package com.nbcb.agent.governance.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolSpecificationGovernancePostProcessorTest {

    @Test
    void shouldReturnUnavailableWithoutCallingOriginalToolWhenChannelBlocked() {
        McpToolChannelBlockMapper mapper = mock(McpToolChannelBlockMapper.class);
        McpToolChannelBlockEntity block = new McpToolChannelBlockEntity();
        block.setToolName("getWeatherByCity");
        block.setChannelCode("CHANNEL_A");
        block.setStatus("DISABLED");
        block.setIsDelete("0");
        when(mapper.findCandidates("getWeatherByCity", "CHANNEL_A"))
                .thenReturn(List.of(block));

        McpToolChannelGovernanceProperties properties =
                new McpToolChannelGovernanceProperties();
        McpToolChannelGovernanceManager manager =
                new McpToolChannelGovernanceManager(
                        mapper,
                        properties,
                        "nacos-mcp-agent");
        manager.initCache();

        AtomicBoolean called = new AtomicBoolean(false);
        McpSchema.Tool tool = new McpSchema.Tool(
                "getWeatherByCity",
                null,
                "weather",
                new McpSchema.JsonSchema(
                        "object",
                        Map.of(),
                        List.of(),
                        true,
                        Map.of(),
                        Map.of()),
                null,
                null,
                null);
        McpServerFeatures.AsyncToolSpecification specification =
                new McpServerFeatures.AsyncToolSpecification(
                        tool,
                        (exchange, args) -> {
                            called.set(true);
                            return Mono.just(new McpSchema.CallToolResult("ok", false));
                        });
        McpToolSpecificationGovernancePostProcessor postProcessor =
                new McpToolSpecificationGovernancePostProcessor(manager);
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.AsyncToolSpecification> wrapped =
                (List<McpServerFeatures.AsyncToolSpecification>)
                        postProcessor.postProcessAfterInitialization(
                                List.of(specification),
                                "localMcpTools");
        McpAsyncServerExchange exchange = mock(McpAsyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(
                Map.of(McpToolChannelContext.CHANNEL_CONTEXT_KEY, "CHANNEL_A")));

        McpSchema.CallToolResult result =
                wrapped.get(0).call().apply(exchange, Map.of()).block();

        assertThat(called).isFalse();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString())
                .contains("TOOL_UNAVAILABLE")
                .contains("getWeatherByCity")
                .contains("CHANNEL_A");
    }
}
