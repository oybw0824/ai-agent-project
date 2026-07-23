package com.nbcb.nacosmcpagent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorMdcConfigurationTest {

    private ReactorMdcConfiguration configuration;

    @BeforeEach
    void setUp() {
        MDC.clear();
        configuration = new ReactorMdcConfiguration();
        configuration.registerReactorMdcBridge();
    }

    @AfterEach
    void tearDown() {
        configuration.resetReactorMdcBridge();
        MDC.clear();
    }

    @Test
    void shouldPropagateMdcToBoundedElasticThread() {
        String traceId = Mono.fromCallable(() -> MDC.get(TraceContextWebFilter.TRACE_ID_KEY))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(Context.of(
                        TraceContextWebFilter.TRACE_ID_KEY, "trace-bounded-elastic",
                        TraceContextWebFilter.SPAN_ID_KEY, "span-bounded-elastic"))
                .block();

        assertThat(traceId).isEqualTo("trace-bounded-elastic");
    }

    @Test
    void shouldNotLeaveTraceMdcOnCallerThreadAfterCompletion() {
        Mono.fromCallable(() -> MDC.get(TraceContextWebFilter.TRACE_ID_KEY))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(Context.of(
                        TraceContextWebFilter.TRACE_ID_KEY, "trace-cleanup",
                        TraceContextWebFilter.SPAN_ID_KEY, "span-cleanup"))
                .block();

        assertThat(MDC.get(TraceContextWebFilter.TRACE_ID_KEY)).isNull();
        assertThat(MDC.get(TraceContextWebFilter.SPAN_ID_KEY)).isNull();
    }
}
