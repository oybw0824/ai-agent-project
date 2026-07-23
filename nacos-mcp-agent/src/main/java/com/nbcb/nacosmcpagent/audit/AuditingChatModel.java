package com.nbcb.nacosmcpagent.audit;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public class AuditingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AgentCallAuditService auditService;
    private final String agentId;
    private final String nodeId;
    private final String modelId;

    public AuditingChatModel(
            ChatModel delegate,
            AgentCallAuditService auditService,
            String agentId,
            String nodeId,
            String modelId) {
        this.delegate = delegate;
        this.auditService = auditService;
        this.agentId = agentId;
        this.nodeId = nodeId;
        this.modelId = modelId;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long started = System.nanoTime();
        String input = AgentAuditPayloads.prompt(prompt);
        try {
            ChatResponse response = delegate.call(prompt);
            auditService.record(AgentCallAuditEvent.model(
                    agentId,
                    nodeId,
                    modelId,
                    input,
                    AgentAuditPayloads.response(response),
                    true,
                    null,
                    durationMs(started)));
            return response;
        }
        catch (RuntimeException ex) {
            auditService.record(AgentCallAuditEvent.model(
                    agentId,
                    nodeId,
                    modelId,
                    input,
                    null,
                    false,
                    ex.toString(),
                    durationMs(started)));
            throw ex;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long started = System.nanoTime();
        String input = AgentAuditPayloads.prompt(prompt);
        StringBuilder output = new StringBuilder();
        return delegate.stream(prompt)
                .doOnNext(response -> output.append(
                        AgentAuditPayloads.response(response)))
                .doOnComplete(() -> auditService.record(
                        AgentCallAuditEvent.model(
                                agentId,
                                nodeId,
                                modelId,
                                input,
                                output.toString(),
                                true,
                                null,
                                durationMs(started))))
                .doOnError(ex -> auditService.record(
                        AgentCallAuditEvent.model(
                                agentId,
                                nodeId,
                                modelId,
                                input,
                                output.toString(),
                                false,
                                ex.toString(),
                                durationMs(started))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static long durationMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
