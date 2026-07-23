package com.nbcb.agent.governance.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 包裹 MCP Server 最终导出的工具规格，统一执行渠道治理。
 */
public class McpToolSpecificationGovernancePostProcessor
        implements BeanPostProcessor, PriorityOrdered {

    private final McpToolChannelGovernanceManager governanceManager;

    public McpToolSpecificationGovernancePostProcessor(
            McpToolChannelGovernanceManager governanceManager) {
        this.governanceManager = governanceManager;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean,
                                                 String beanName)
            throws BeansException {
        if (!(bean instanceof List<?> list) || list.isEmpty()) {
            return bean;
        }
        if (list.stream().allMatch(McpServerFeatures.AsyncToolSpecification.class::isInstance)) {
            return list.stream()
                    .map(McpServerFeatures.AsyncToolSpecification.class::cast)
                    .map(this::wrapAsync)
                    .toList();
        }
        if (list.stream().allMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return list.stream()
                    .map(McpServerFeatures.SyncToolSpecification.class::cast)
                    .map(this::wrapSync)
                    .toList();
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private McpServerFeatures.AsyncToolSpecification wrapAsync(
            McpServerFeatures.AsyncToolSpecification specification) {
        BiFunction<McpAsyncServerExchange, Map<String, Object>,
                Mono<McpSchema.CallToolResult>> originalCall =
                specification.call();
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest,
                Mono<McpSchema.CallToolResult>> originalHandler =
                specification.callHandler();

        BiFunction<McpAsyncServerExchange, Map<String, Object>,
                Mono<McpSchema.CallToolResult>> guardedCall =
                (exchange, arguments) -> Mono.deferContextual(context -> {
                    McpToolChannelGovernanceDecision decision =
                            check(specification.tool().name(), exchange, context);
                    if (decision.blocked()) {
                        return Mono.just(rejectedResult(
                                specification.tool().name(),
                                resolveChannel(exchange.transportContext(), context),
                                decision));
                    }
                    return originalCall.apply(exchange, arguments);
                });

        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest,
                Mono<McpSchema.CallToolResult>> guardedHandler =
                originalHandler == null ? null : (exchange, request) ->
                        Mono.deferContextual(context -> {
                            McpToolChannelGovernanceDecision decision =
                                    check(specification.tool().name(), exchange, context);
                            if (decision.blocked()) {
                                return Mono.just(rejectedResult(
                                        specification.tool().name(),
                                        resolveChannel(exchange.transportContext(), context),
                                        decision));
                            }
                            return originalHandler.apply(exchange, request);
                        });

        return new McpServerFeatures.AsyncToolSpecification(
                specification.tool(),
                guardedCall,
                guardedHandler);
    }

    private McpServerFeatures.SyncToolSpecification wrapSync(
            McpServerFeatures.SyncToolSpecification specification) {
        BiFunction<McpSyncServerExchange, Map<String, Object>,
                McpSchema.CallToolResult> originalCall =
                specification.call();
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest,
                McpSchema.CallToolResult> originalHandler =
                specification.callHandler();

        BiFunction<McpSyncServerExchange, Map<String, Object>,
                McpSchema.CallToolResult> guardedCall =
                (exchange, arguments) -> {
                    String channelCode = resolveChannel(exchange.transportContext(), null);
                    McpToolChannelGovernanceDecision decision =
                            governanceManager.check(
                                    specification.tool().name(),
                                    channelCode);
                    if (decision.blocked()) {
                        return rejectedResult(
                                specification.tool().name(),
                                channelCode,
                                decision);
                    }
                    return originalCall.apply(exchange, arguments);
                };

        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest,
                McpSchema.CallToolResult> guardedHandler =
                originalHandler == null ? null : (exchange, request) -> {
                    String channelCode = resolveChannel(exchange.transportContext(), null);
                    McpToolChannelGovernanceDecision decision =
                            governanceManager.check(
                                    specification.tool().name(),
                                    channelCode);
                    if (decision.blocked()) {
                        return rejectedResult(
                                specification.tool().name(),
                                channelCode,
                                decision);
                    }
                    return originalHandler.apply(exchange, request);
                };

        return new McpServerFeatures.SyncToolSpecification(
                specification.tool(),
                guardedCall,
                guardedHandler);
    }

    private McpToolChannelGovernanceDecision check(
            String toolName,
            McpAsyncServerExchange exchange,
            ContextView context) {
        return governanceManager.check(
                toolName,
                resolveChannel(exchange.transportContext(), context));
    }

    private String resolveChannel(McpTransportContext transportContext,
                                  ContextView context) {
        if (transportContext != null) {
            Object value = transportContext.get(
                    McpToolChannelContext.CHANNEL_CONTEXT_KEY);
            if (value instanceof String channelCode
                    && StringUtils.hasText(channelCode)) {
                return channelCode.trim();
            }
        }
        if (context != null
                && context.hasKey(McpToolChannelContext.CHANNEL_CONTEXT_KEY)) {
            Object value = context.get(McpToolChannelContext.CHANNEL_CONTEXT_KEY);
            if (value instanceof String channelCode
                    && StringUtils.hasText(channelCode)) {
                return channelCode.trim();
            }
        }
        return null;
    }

    private McpSchema.CallToolResult rejectedResult(
            String toolName,
            String channelCode,
            McpToolChannelGovernanceDecision decision) {
        return new McpSchema.CallToolResult(
                rejectedJson(toolName, channelCode, decision),
                true);
    }

    private String rejectedJson(String toolName,
                                String channelCode,
                                McpToolChannelGovernanceDecision decision) {
        return "{\"success\":false"
                + ",\"reason\":\"" + escape(decision.reason()) + "\""
                + ",\"userMessage\":\"" + escape(decision.message()) + "\""
                + ",\"retryable\":false"
                + ",\"toolName\":\"" + escape(toolName) + "\""
                + ",\"channelCode\":\"" + escape(channelCode) + "\""
                + "}";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
