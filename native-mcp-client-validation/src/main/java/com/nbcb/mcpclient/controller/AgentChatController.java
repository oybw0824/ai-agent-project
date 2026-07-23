package com.nbcb.mcpclient.controller;

import com.nbcb.mcpclient.domain.AgentChatRequest;
import com.nbcb.mcpclient.domain.AgentChatResponse;
import com.nbcb.mcpclient.service.AgentChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ReactAgent 验证接口。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentChatController {

    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(
            @Valid @RequestBody AgentChatRequest request) {
        return new AgentChatResponse(agentChatService.chat(request.question()));
    }
}
