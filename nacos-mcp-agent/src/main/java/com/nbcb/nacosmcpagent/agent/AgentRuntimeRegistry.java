package com.nbcb.nacosmcpagent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.nbcb.nacosmcpagent.agent.ConfiguredToolResolver.ToolSnapshot;
import com.nbcb.nacosmcpagent.domain.AgentDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.AgentNodeDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.SkillDefinition;
import com.nbcb.nacosmcpagent.repository.AgentDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds and keeps published Agent runtimes loaded from database definitions.
 */
@Slf4j
public class AgentRuntimeRegistry implements SmartInitializingSingleton {

    private static final String OUTPUT_KEY = "agent_result";

    private final AgentDefinitionRepository definitionRepository;
    private final DatabaseChatModelFactory chatModelFactory;
    private final ConfiguredToolResolver toolResolver;
    private final AgentSkillRegistryFactory skillRegistryFactory;
    private final PromptTemplateLoader promptLoader;
    private boolean initialized;

    private volatile Map<String, AgentRuntime> runtimes = Map.of();
    private volatile Map<NodeKey, NodeRuntime> nodeRuntimes = Map.of();
    private volatile String defaultAgentId;

    public AgentRuntimeRegistry(
            AgentDefinitionRepository definitionRepository,
            DatabaseChatModelFactory chatModelFactory,
            ConfiguredToolResolver toolResolver,
            AgentSkillRegistryFactory skillRegistryFactory,
            PromptTemplateLoader promptLoader) {
        this.definitionRepository = definitionRepository;
        this.chatModelFactory = chatModelFactory;
        this.toolResolver = toolResolver;
        this.skillRegistryFactory = skillRegistryFactory;
        this.promptLoader = promptLoader;
    }

    @Override
    public void afterSingletonsInstantiated() {
        initialize();
    }

    /**
     * Runs once on Spring startup. Public for unit tests to verify idempotency.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            List<AgentDefinition> definitions =
                    definitionRepository.findPublishedAgents();
            if (definitions.isEmpty()) {
                throw new IllegalStateException(
                        "No published Agent configuration found");
            }
            ToolSnapshot toolSnapshot = toolResolver.snapshot();
            Map<String, AgentRuntime> built = definitions.stream()
                    .map(definition -> buildRuntime(
                            definition, toolSnapshot))
                    .collect(Collectors.toMap(
                            runtime -> runtime.definition().agentId(),
                            Function.identity(),
                            (left, right) -> {
                                throw new IllegalStateException(
                                        "Duplicate AGENT_ID: "
                                                + left.definition().agentId());
                            },
                            LinkedHashMap::new));
            Map<NodeKey, NodeRuntime> builtNodes = new LinkedHashMap<>();
            built.forEach((agentId, runtime) -> runtime.nodes().forEach(node -> {
                NodeKey key = new NodeKey(agentId, node.nodeId());
                NodeRuntime previous = builtNodes.putIfAbsent(key, node);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate Agent node: agentId=" + agentId
                                    + ", nodeId=" + node.nodeId());
                }
            }));
            defaultAgentId = definitions.get(0).agentId();
            runtimes = Map.copyOf(built);
            nodeRuntimes = Map.copyOf(builtNodes);
            log.info("Agent runtime initialized: defaultAgentId={}, agents={}",
                    defaultAgentId, runtimes.keySet());
        }
        catch (RuntimeException ex) {
            initialized = false;
            throw ex;
        }
    }

    public String chat(String agentId, String nodeId, String question) {
        NodeRuntime nodeRuntime = requireNodeRuntime(agentId, nodeId);
        try {
            String answer = nodeRuntime.call(question);
            return answer == null ? "" : answer;
        }
        catch (GraphRunnerException ex) {
            throw new IllegalStateException(
                    "ReactAgent execution failed: agentId=" + agentId
                            + ", nodeId=" + nodeId, ex);
        }
    }

    public Flux<String> stream(String agentId, String nodeId, String question) {
        NodeRuntime nodeRuntime = requireNodeRuntime(agentId, nodeId);
        try {
            return nodeRuntime.stream(question);
        }
        catch (GraphRunnerException ex) {
            return Flux.error(new IllegalStateException(
                    "ReactAgent stream failed: agentId=" + agentId
                            + ", nodeId=" + nodeId, ex));
        }
    }

    public List<AgentRuntimeView> listAgents() {
        return runtimes.values().stream()
                .map(runtime -> new AgentRuntimeView(
                        runtime.definition().agentId(),
                        runtime.definition().agentName(),
                        runtime.definition().nodes().stream()
                                .map(AgentNodeDefinition::nodeId)
                                .toList(),
                        runtime.definition().nodes().stream()
                                .map(node -> node.model().modelId())
                                .toList(),
                        runtime.definition().nodes().stream()
                                .flatMap(node -> node.skills().stream())
                                .map(SkillDefinition::skillCode)
                                .distinct()
                                .toList()))
                .toList();
    }

    private AgentRuntime buildRuntime(
            AgentDefinition definition,
            ToolSnapshot snapshot) {
        List<NodeRuntime> nodes = definition.nodes().stream()
                .map(node -> buildNodeRuntime(
                        definition, node, snapshot))
                .toList();
        log.info("Built Agent: agentId={}, nodes={}",
                definition.agentId(),
                nodes.stream().map(NodeRuntime::nodeId).toList());
        return new AgentRuntime(definition, nodes);
    }

    private NodeRuntime buildNodeRuntime(
            AgentDefinition definition,
            AgentNodeDefinition node,
            ToolSnapshot snapshot) {
        ChatModel chatModel = chatModelFactory.create(
                node.model(), node, definition.agentId());
        String systemPrompt = promptLoader.loadRequired(
                node.systemPrompt(),
                "AI_AGENT_NODE.NODE_SYSTEM_PROMPT");
        String userPrompt = promptLoader.load(
                node.userPrompt());
        if (!node.requiresAgent()) {
            return new ChatModelNodeRuntime(
                    node.nodeId(), chatModel, systemPrompt, userPrompt);
        }

        Map<String, List<AgentDefinition.ToolDefinition>> configuredGroups =
                node.skills().stream()
                        .collect(Collectors.toMap(
                                SkillDefinition::skillCode,
                                SkillDefinition::tools,
                                (left, right) -> {
                                    throw new IllegalStateException(
                                            "Duplicate Skill binding: agentId="
                                                    + definition.agentId()
                                                    + ", nodeId="
                                                    + node.nodeId());
                                },
                                LinkedHashMap::new));
        SkillRegistry registry = skillRegistryFactory.create(
                definition.agentId(), configuredGroups.keySet());
        Map<String, List<AgentDefinition.ToolDefinition>> availableGroups =
                configuredGroups.entrySet().stream()
                        .filter(entry -> registry.contains(entry.getKey()))
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> right,
                                LinkedHashMap::new));
        Map<String, List<ToolCallback>> groupedTools =
                toolResolver.resolveGroupedTools(
                        definition.agentId(),
                        node.nodeId(),
                        availableGroups,
                        snapshot);
        List<ToolCallback> agentTools = toolResolver.resolveTools(
                definition.agentId(),
                node.nodeId(),
                node.agentTools(),
                snapshot);
        List<ToolCallback> directAgentTools = removeToolsAlreadyProvidedBySkills(
                agentTools, groupedTools);

        var builder = ReactAgent.builder()
                .name(definition.agentId() + "-" + node.nodeId())
                .description(definition.agentName())
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .outputKey(OUTPUT_KEY)
                .enableLogging(true);
        if (!groupedTools.isEmpty()) {
            SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                    .skillRegistry(registry)
                    .groupedTools(groupedTools)
                    .build();
            builder.hooks(skillsHook);
        }
        if (!directAgentTools.isEmpty()) {
            builder.tools(directAgentTools.toArray(ToolCallback[]::new));
        }
        ReactAgent agent = builder.build();
        log.info("Built Agent node: agentId={}, nodeId={}, modelId={}, skills={}, agentTools={}",
                definition.agentId(),
                node.nodeId(),
                node.model().modelId(),
                groupedTools.keySet(),
                directAgentTools.stream()
                        .map(tool -> tool.getToolDefinition().name())
                        .toList());
        return new ReactAgentNodeRuntime(node.nodeId(), agent, userPrompt);
    }

    private NodeRuntime requireNodeRuntime(String agentId, String nodeId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        if (!StringUtils.hasText(nodeId)) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        NodeKey key = new NodeKey(agentId.trim(), nodeId.trim());
        NodeRuntime nodeRuntime = nodeRuntimes.get(key);
        if (nodeRuntime == null) {
            throw new IllegalArgumentException(
                    "Agent node not found or unpublished: agentId="
                            + key.agentId() + ", nodeId=" + key.nodeId());
        }
        return nodeRuntime;
    }

    private static String applyUserPrompt(
            String userPrompt,
            String question) {
        if (!StringUtils.hasText(userPrompt)) {
            return question;
        }
        if (userPrompt.contains("${question}")) {
            return userPrompt.replace("${question}", question);
        }
        return userPrompt + System.lineSeparator() + question;
    }

    private static String responseText(ChatResponse response) {
        return response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                ? "" : response.getResult().getOutput().getText();
    }

    private static List<ToolCallback> removeToolsAlreadyProvidedBySkills(
            List<ToolCallback> agentTools,
            Map<String, List<ToolCallback>> groupedTools) {
        if (agentTools.isEmpty() || groupedTools.isEmpty()) {
            return distinctByToolName(agentTools);
        }
        Map<String, ToolCallback> skillToolsByName = groupedTools.values()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        tool -> tool.getToolDefinition().name(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return distinctByToolName(agentTools).stream()
                .filter(tool -> !skillToolsByName.containsKey(
                        tool.getToolDefinition().name()))
                .toList();
    }

    private static List<ToolCallback> distinctByToolName(
            List<ToolCallback> tools) {
        return tools.stream()
                .collect(Collectors.toMap(
                        tool -> tool.getToolDefinition().name(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private interface NodeRuntime {

        String nodeId();

        String call(String input)
                throws GraphRunnerException;

        Flux<String> stream(String input)
                throws GraphRunnerException;
    }

    private record ReactAgentNodeRuntime(
            String nodeId,
            ReactAgent agent,
            String userPrompt) implements NodeRuntime {

        @Override
        public String call(String input)
                throws GraphRunnerException {
            AssistantMessage response = agent.call(
                    applyUserPrompt(userPrompt, input));
            return response == null || response.getText() == null
                    ? "" : response.getText();
        }

        @Override
        public Flux<String> stream(String input)
                throws GraphRunnerException {
            return agent.streamMessages(applyUserPrompt(userPrompt, input))
                    .filter(AssistantMessage.class::isInstance)
                    .cast(AssistantMessage.class)
                    .map(AssistantMessage::getText)
                    .filter(StringUtils::hasText);
        }
    }

    private record ChatModelNodeRuntime(
            String nodeId,
            ChatModel chatModel,
            String systemPrompt,
            String userPrompt) implements NodeRuntime {

        @Override
        public String call(String input) {
            ChatResponse response = chatModel.call(prompt(input));
            return responseText(response);
        }

        @Override
        public Flux<String> stream(String input) {
            return chatModel.stream(prompt(input))
                    .map(AgentRuntimeRegistry::responseText)
                    .filter(StringUtils::hasText);
        }

        private Prompt prompt(String input) {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(applyUserPrompt(userPrompt, input)));
            return new Prompt(messages);
        }
    }

    private record NodeKey(
            String agentId,
            String nodeId) {
    }

    private record AgentRuntime(
            AgentDefinition definition,
            List<NodeRuntime> nodes) {
    }

    public record AgentRuntimeView(
            String agentId,
            String agentName,
            List<String> nodeIds,
            List<String> modelIds,
            List<String> skills) {
    }
}
