package com.nbcb.nacosmcpagent.agent;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.nbcb.nacosmcpagent.agent.ConfiguredToolResolver.ToolSnapshot;
import com.nbcb.nacosmcpagent.domain.AgentDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.AgentNodeDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ModelDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.SkillDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ToolDefinition;
import com.nbcb.nacosmcpagent.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeRegistryTest {

    @Test
    void shouldCreateIndependentModelPerAgentAndReuseRuntime() {
        AgentDefinitionRepository repository =
                mock(AgentDefinitionRepository.class);
        DatabaseChatModelFactory modelFactory =
                mock(DatabaseChatModelFactory.class);
        ConfiguredToolResolver toolResolver =
                mock(ConfiguredToolResolver.class);
        AgentSkillRegistryFactory skillFactory =
                mock(AgentSkillRegistryFactory.class);
        ToolCallback creditTool = mock(ToolCallback.class);

        when(repository.findPublishedAgents()).thenReturn(List.of(
                definition("agent-a", "node-a"),
                definition("agent-b", "node-b")));
        when(modelFactory.create(any(), any(), any())).thenReturn(
                new FixedChatModel("Agent A"),
                new FixedChatModel("Agent B"));
        when(toolResolver.snapshot())
                .thenReturn(new ToolSnapshot(List.of(), List.of(), null));
        when(toolResolver.resolveGroupedTools(any(), any(), any(), any()))
                .thenReturn(Map.of(
                        "enterprise-credit-query",
                        List.of(creditTool)));
        when(toolResolver.resolveTools(any(), any(), any(), any()))
                .thenReturn(List.of());
        SkillRegistry boundSkillRegistry = skillRegistry();
        when(skillFactory.create(any(), any()))
                .thenReturn(boundSkillRegistry);

        AgentRuntimeRegistry registry = registry(
                repository,
                modelFactory,
                toolResolver,
                skillFactory);

        registry.initialize();

        assertThat(registry.chat("agent-a", "node-a", "hello"))
                .isEqualTo("Agent A");
        assertThat(registry.chat("agent-b", "node-b", "hello"))
                .isEqualTo("Agent B");
        assertThat(registry.chat("agent-a", "node-a", "hello again"))
                .isEqualTo("Agent A");
        assertThat(registry.stream("agent-a", "node-a", "stream")
                .collectList()
                .block())
                .contains("Agent A");
        assertThatThrownBy(() -> registry.chat("agent-a", "missing-node", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId=agent-a")
                .hasMessageContaining("nodeId=missing-node");
        registry.initialize();

        verify(modelFactory, times(2)).create(any(), any(), any());
        verify(toolResolver, times(1)).snapshot();
        assertThat(registry.listAgents())
                .extracting(AgentRuntimeRegistry.AgentRuntimeView::agentId)
                .containsExactlyInAnyOrder("agent-a", "agent-b");
        assertThat(registry.listAgents().toString())
                .doesNotContain("database-api-key");
    }

    @Test
    void shouldBuildPlainChatModelNodeWhenNodeHasNoSkillOrTool() {
        AgentDefinitionRepository repository =
                mock(AgentDefinitionRepository.class);
        DatabaseChatModelFactory modelFactory =
                mock(DatabaseChatModelFactory.class);
        ConfiguredToolResolver toolResolver =
                mock(ConfiguredToolResolver.class);
        AgentSkillRegistryFactory skillFactory =
                mock(AgentSkillRegistryFactory.class);

        when(repository.findPublishedAgents()).thenReturn(List.of(
                plainDefinition("plain-agent", "plain-node")));
        when(modelFactory.create(any(), any(), any())).thenReturn(
                new FixedChatModel("plain answer"));
        when(toolResolver.snapshot())
                .thenReturn(new ToolSnapshot(List.of(), List.of(), null));

        AgentRuntimeRegistry registry = registry(
                repository,
                modelFactory,
                toolResolver,
                skillFactory);

        registry.initialize();

        assertThat(registry.chat("plain-agent", "plain-node", "hello"))
                .isEqualTo("plain answer");
        assertThat(registry.stream("plain-agent", "plain-node", "stream")
                .collectList()
                .block())
                .containsExactly("plain answer");
        verify(skillFactory, times(0)).create(any(), any());
        verify(toolResolver, times(0))
                .resolveGroupedTools(any(), any(), any(), any());
    }

    @Test
    void shouldCallOnlySelectedNodeWithinSameAgent() {
        AgentDefinitionRepository repository =
                mock(AgentDefinitionRepository.class);
        DatabaseChatModelFactory modelFactory =
                mock(DatabaseChatModelFactory.class);
        ConfiguredToolResolver toolResolver =
                mock(ConfiguredToolResolver.class);
        AgentSkillRegistryFactory skillFactory =
                mock(AgentSkillRegistryFactory.class);
        RecordingChatModel first = new RecordingChatModel("first");
        RecordingChatModel second = new RecordingChatModel("second");

        when(repository.findPublishedAgents()).thenReturn(List.of(
                new AgentDefinition(
                        "agent-a",
                        "agent-a",
                        List.of(
                                plainNode("node-1"),
                                plainNode("node-2")))));
        when(modelFactory.create(any(), any(), any())).thenReturn(first, second);
        when(toolResolver.snapshot())
                .thenReturn(new ToolSnapshot(List.of(), List.of(), null));

        AgentRuntimeRegistry registry = registry(
                repository,
                modelFactory,
                toolResolver,
                skillFactory);

        registry.initialize();

        assertThat(registry.chat("agent-a", "node-2", "hello"))
                .isEqualTo("second");
        assertThat(first.calls()).isZero();
        assertThat(second.calls()).isOne();
    }

    private static AgentRuntimeRegistry registry(
            AgentDefinitionRepository repository,
            DatabaseChatModelFactory modelFactory,
            ConfiguredToolResolver toolResolver,
            AgentSkillRegistryFactory skillFactory) {
        return new AgentRuntimeRegistry(
                repository,
                modelFactory,
                toolResolver,
                skillFactory,
                new PromptTemplateLoader(
                        new DefaultResourceLoader(),
                        new StandardEnvironment()));
    }

    private static AgentDefinition definition(
            String agentId,
            String nodeId) {
        ModelDefinition sharedModel = model("shared-model");
        SkillDefinition skill = new SkillDefinition(
                "enterprise-credit-query",
                List.of(new ToolDefinition(
                        "nacos-mcp-agent",
                        "queryEnterpriseCredit")));
        return new AgentDefinition(
                agentId,
                agentId,
                List.of(new AgentNodeDefinition(
                        nodeId,
                        "classpath:prompt/system-prompt.md",
                        "",
                        new BigDecimal("0.20"),
                        2048,
                        sharedModel,
                        List.of(skill),
                        List.of())));
    }

    private static AgentDefinition plainDefinition(
            String agentId,
            String nodeId) {
        return new AgentDefinition(
                agentId,
                agentId,
                List.of(plainNode(nodeId)));
    }

    private static AgentNodeDefinition plainNode(String nodeId) {
        return new AgentNodeDefinition(
                nodeId,
                "classpath:prompt/system-prompt.md",
                "",
                null,
                null,
                model("plain-model-" + nodeId),
                List.of(),
                List.of());
    }

    private static ModelDefinition model(String modelId) {
        return new ModelDefinition(
                modelId,
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                "database-api-key");
    }

    private static SkillRegistry skillRegistry() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.contains("enterprise-credit-query")).thenReturn(true);
        when(registry.listAll()).thenReturn(List.of());
        when(registry.getSkillLoadInstructions()).thenReturn("");
        return registry;
    }

    private record FixedChatModel(String answer) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return response(answer);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(response(answer));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    private static final class RecordingChatModel implements ChatModel {

        private final String answer;

        private final AtomicInteger calls = new AtomicInteger();

        private RecordingChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return response(answer);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls.incrementAndGet();
            return Flux.just(response(answer));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }

        int calls() {
            return calls.get();
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text))));
    }
}
