package com.nbcb.nacosmcpagent.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 统一处理 WebFlux API 请求的 trace 上下文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextWebFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String TRACE_ID_KEY = "traceId";
    public static final String SPAN_ID_KEY = "spanId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders requestHeaders = exchange.getRequest().getHeaders();
        String traceId = getOrGenerateTraceId(requestHeaders);
        String spanId = getOrGenerateSpanId(requestHeaders);

        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        exchange.getResponse().getHeaders().set(SPAN_ID_HEADER, spanId);

        return chain.filter(exchange)
                .contextWrite(context -> context
                        .put(TRACE_ID_KEY, traceId)
                        .put(SPAN_ID_KEY, spanId));
    }

    private String getOrGenerateTraceId(HttpHeaders headers) {
        String traceId = headers.getFirst(TRACE_ID_HEADER);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }

    private String getOrGenerateSpanId(HttpHeaders headers) {
        String spanId = headers.getFirst(SPAN_ID_HEADER);
        if (StringUtils.hasText(spanId)) {
            return spanId;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
