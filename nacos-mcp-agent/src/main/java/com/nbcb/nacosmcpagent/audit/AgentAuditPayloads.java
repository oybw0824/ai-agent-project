package com.nbcb.nacosmcpagent.audit;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.stream.Collectors;

final class AgentAuditPayloads {

    private AgentAuditPayloads() {
    }

    static String prompt(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        String messages = prompt.getInstructions().stream()
                .map(AgentAuditPayloads::message)
                .collect(Collectors.joining("\n\n"));
        return "messages:\n" + messages
                + "\n\noptions:\n" + prompt.getOptions();
    }

    static String response(ChatResponse response) {
        return response == null ? "" : response.toString();
    }

    private static String message(Message message) {
        if (message == null) {
            return "";
        }
        return message.getMessageType() + ":\n" + message.getText()
                + "\nmetadata:\n" + message.getMetadata();
    }
}
