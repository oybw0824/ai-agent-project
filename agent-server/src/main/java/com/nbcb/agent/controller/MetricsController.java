package com.nbcb.agent.controller;

import com.nbcb.agent.metric.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MetricsController {

    private final AgentMetrics metrics;
    private final MeterRegistry registry;

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("chat", buildChatMetrics());
        result.put("sse", buildSseMetrics());
        result.put("tool", buildToolMetrics());
        result.put("skillGen", buildSkillGenMetrics());
        result.put("nacos", buildNacosMetrics());
        result.put("threadPool", buildThreadPoolMetrics());

        return result;
    }

    private Map<String, Object> buildChatMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (long) metrics.chatTotal.count());
        m.put("success", (long) metrics.chatSuccess.count());
        m.put("failure", (long) metrics.chatFailure.count());
        m.put("retry", (long) metrics.chatRetry.count());
        m.put("durationAvg", String.format("%.0fms", metrics.chatDuration.mean(TimeUnit.MILLISECONDS)));
        return m;
    }

    private Map<String, Object> buildSseMetrics() {
        long connections = (long) metrics.sseConnections.count();
        long completed = (long) metrics.sseCompleted.count();
        long timeout = (long) metrics.sseTimeout.count();
        long error = (long) metrics.sseError.count();
        long active = connections - completed - timeout - error;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connections", connections);
        m.put("completed", completed);
        m.put("timeout", timeout);
        m.put("error", error);
        m.put("active", active);
        m.put("durationAvg", String.format("%.0fms", metrics.sseDuration.mean(TimeUnit.MILLISECONDS)));
        return m;
    }

    private Map<String, Object> buildToolMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (long) metrics.toolCallTotal.count());
        m.put("success", (long) metrics.toolCallSuccess.count());
        m.put("failure", (long) metrics.toolCallFailure.count());
        return m;
    }

    private Map<String, Object> buildSkillGenMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (long) metrics.skillGenTotal.count());
        m.put("success", (long) metrics.skillGenSuccess.count());
        m.put("failure", (long) metrics.skillGenFailure.count());
        m.put("durationAvg", String.format("%.0fms", metrics.skillGenDuration.mean(TimeUnit.MILLISECONDS)));
        m.put("phaseDecompose", String.format("%.0fms", metrics.skillGenPhaseDecompose.mean(TimeUnit.MILLISECONDS)));
        m.put("phaseToolMap", String.format("%.0fms", metrics.skillGenPhaseToolMap.mean(TimeUnit.MILLISECONDS)));
        m.put("phaseStepGen", String.format("%.0fms", metrics.skillGenPhaseStepGen.mean(TimeUnit.MILLISECONDS)));
        m.put("phaseAssembly", String.format("%.0fms", metrics.skillGenPhaseAssembly.mean(TimeUnit.MILLISECONDS)));
        return m;
    }

    private Map<String, Object> buildNacosMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", metrics.nacosConnected.get());
        m.put("loadedSkills", metrics.nacosLoadedSkillCount.get());
        return m;
    }

    private Map<String, Object> buildThreadPoolMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gauge", Search.in(registry).name(n -> n.startsWith("agent.threadpool")).gauges()
                .stream()
                .collect(LinkedHashMap::new,
                        (map, g) -> map.put(g.getId().getName() + "." + g.getId().getTag("pool"),
                                String.format("%.0f", g.value())),
                        LinkedHashMap::putAll));
        return m;
    }
}