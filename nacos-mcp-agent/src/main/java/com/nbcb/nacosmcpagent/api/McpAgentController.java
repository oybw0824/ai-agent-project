package com.nbcb.nacosmcpagent.api;

import com.nbcb.nacosmcpagent.agent.AgentRuntimeRegistry;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Simple Agent chat validation API.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(
        prefix = "agent.runtime",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class McpAgentController {

    private final AgentRuntimeRegistry agentRegistry;

    public McpAgentController(AgentRuntimeRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @PostMapping("/agent/chat")
    public Mono<AgentChatResponse> chat(
            @Valid @RequestBody AgentChatRequest request) {
        return Mono.fromCallable(() -> new AgentChatResponse(
                        agentRegistry.chat(
                                request.agentId(),
                                request.nodeId(),
                                request.question())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(
            value = "/agent/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @Valid @RequestBody AgentChatRequest request) {
        return Flux.defer(() -> agentRegistry.stream(
                                request.agentId(),
                                request.nodeId(),
                                request.question())
                        .map(McpAgentController::messageEvent)
                        .concatWithValues(doneEvent()))
                .onErrorResume(ex -> Flux.just(errorEvent(ex)));
    }

    @GetMapping("/agents")
    public List<AgentRuntimeRegistry.AgentRuntimeView> agents() {
        return agentRegistry.listAgents();
    }

    private static ServerSentEvent<String> messageEvent(String text) {
        return ServerSentEvent.builder(text)
                .event("message")
                .build();
    }

    private static ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.builder("[DONE]")
                .event("done")
                .build();
    }

    private static ServerSentEvent<String> errorEvent(Throwable ex) {
        return ServerSentEvent.builder(ex.getMessage())
                .event("error")
                .build();
    }
}
