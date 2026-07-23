package com.nbcb.agent.governance.mcp;

import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 将渠道请求头写入 WebFlux Reactor Context。
 */
public class McpToolChannelContextWebFilter implements WebFilter {

    private final McpToolChannelGovernanceProperties properties;

    public McpToolChannelContextWebFilter(
            McpToolChannelGovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             WebFilterChain chain) {
        String channelCode = exchange.getRequest()
                .getHeaders()
                .getFirst(properties.getChannelHeader());
        if (!StringUtils.hasText(channelCode)) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange)
                .contextWrite(context -> context.put(
                        McpToolChannelContext.CHANNEL_CONTEXT_KEY,
                        channelCode.trim()));
    }
}
