package com.nbcb.nacosmcpagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextWebFilterTest {

    private final TraceContextWebFilter filter = new TraceContextWebFilter();

    @Test
    void shouldGenerateTraceHeadersWhenRequestHeadersAreMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/agents").build());

        filter.filter(exchange, successfulChain()).block();

        assertThat(exchange.getResponse().getHeaders()
                .getFirst(TraceContextWebFilter.TRACE_ID_HEADER))
                .isNotBlank();
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(TraceContextWebFilter.SPAN_ID_HEADER))
                .isNotBlank()
                .hasSize(16);
    }

    @Test
    void shouldPropagateRequestTraceHeadersToResponseHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/agents")
                        .header(TraceContextWebFilter.TRACE_ID_HEADER, "trace-from-client")
                        .header(TraceContextWebFilter.SPAN_ID_HEADER, "span-from-client")
                        .build());

        filter.filter(exchange, successfulChain()).block();

        assertThat(exchange.getResponse().getHeaders()
                .getFirst(TraceContextWebFilter.TRACE_ID_HEADER))
                .isEqualTo("trace-from-client");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(TraceContextWebFilter.SPAN_ID_HEADER))
                .isEqualTo("span-from-client");
    }

    @Test
    void shouldWriteTraceValuesToReactorContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/agents")
                        .header(TraceContextWebFilter.TRACE_ID_HEADER, "trace-context")
                        .header(TraceContextWebFilter.SPAN_ID_HEADER, "span-context")
                        .build());
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> spanId = new AtomicReference<>();
        WebFilterChain chain = webExchange -> Mono.deferContextual(context -> {
            traceId.set(context.get(TraceContextWebFilter.TRACE_ID_KEY));
            spanId.set(context.get(TraceContextWebFilter.SPAN_ID_KEY));
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        assertThat(traceId).hasValue("trace-context");
        assertThat(spanId).hasValue("span-context");
    }

    private WebFilterChain successfulChain() {
        return exchange -> Mono.empty();
    }
}
