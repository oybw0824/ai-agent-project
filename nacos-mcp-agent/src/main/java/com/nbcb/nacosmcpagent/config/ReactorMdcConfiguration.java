package com.nbcb.nacosmcpagent.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Map;

/**
 * 将 Reactor Context 中的 trace 信息统一恢复到 MDC。
 */
@Configuration
public class ReactorMdcConfiguration {

    private static final String MDC_HOOK_KEY = "nacosMcpAgentMdcContext";

    @PostConstruct
    public void registerReactorMdcBridge() {
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.removeThreadLocalAccessor(TraceContextWebFilter.TRACE_ID_KEY);
        registry.removeThreadLocalAccessor(TraceContextWebFilter.SPAN_ID_KEY);
        registry.registerThreadLocalAccessor(
                TraceContextWebFilter.TRACE_ID_KEY,
                () -> MDC.get(TraceContextWebFilter.TRACE_ID_KEY),
                value -> putOrRemoveMdcValue(TraceContextWebFilter.TRACE_ID_KEY, value),
                () -> MDC.remove(TraceContextWebFilter.TRACE_ID_KEY));
        registry.registerThreadLocalAccessor(
                TraceContextWebFilter.SPAN_ID_KEY,
                () -> MDC.get(TraceContextWebFilter.SPAN_ID_KEY),
                value -> putOrRemoveMdcValue(TraceContextWebFilter.SPAN_ID_KEY, value),
                () -> MDC.remove(TraceContextWebFilter.SPAN_ID_KEY));

        Hooks.enableAutomaticContextPropagation();
        Hooks.onEachOperator(MDC_HOOK_KEY, Operators.lift(
                (scannable, subscriber) -> new MdcContextSubscriber<>(subscriber)));
    }

    @PreDestroy
    public void resetReactorMdcBridge() {
        Hooks.resetOnEachOperator(MDC_HOOK_KEY);
        Hooks.disableAutomaticContextPropagation();
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.removeThreadLocalAccessor(TraceContextWebFilter.TRACE_ID_KEY);
        registry.removeThreadLocalAccessor(TraceContextWebFilter.SPAN_ID_KEY);
        MDC.clear();
    }

    private static void putOrRemoveMdcValue(String key, Object value) {
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            MDC.put(key, stringValue);
            return;
        }
        MDC.remove(key);
    }

    private static final class MdcContextSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<? super T> actual;

        private MdcContextSubscriber(CoreSubscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            withMdcContext(() -> actual.onSubscribe(subscription));
        }

        @Override
        public void onNext(T value) {
            withMdcContext(() -> actual.onNext(value));
        }

        @Override
        public void onError(Throwable throwable) {
            withMdcContext(() -> actual.onError(throwable));
        }

        @Override
        public void onComplete() {
            withMdcContext(actual::onComplete);
        }

        private void withMdcContext(Runnable runnable) {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                copyTraceContextToMdc(currentContext());
                runnable.run();
            }
            finally {
                restoreMdc(previousContext);
            }
        }

        private void copyTraceContextToMdc(ContextView context) {
            putContextValueToMdc(context, TraceContextWebFilter.TRACE_ID_KEY);
            putContextValueToMdc(context, TraceContextWebFilter.SPAN_ID_KEY);
        }

        private void putContextValueToMdc(ContextView context, String key) {
            if (context.hasKey(key)) {
                putOrRemoveMdcValue(key, context.get(key));
                return;
            }
            MDC.remove(key);
        }

        private void restoreMdc(Map<String, String> previousContext) {
            if (previousContext == null || previousContext.isEmpty()) {
                MDC.clear();
                return;
            }
            MDC.setContextMap(previousContext);
        }
    }
}
