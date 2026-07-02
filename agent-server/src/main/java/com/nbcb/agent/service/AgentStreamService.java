package com.nbcb.agent.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.domain.ToolCallRecord;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.skill.LoggingToolCallback;
import com.nbcb.agent.skill.NacosSkillRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AgentStreamService {

    private final ReactAgent agent;

    private final AgentMetrics metrics;

    @Value("${agent.stream.timeout-seconds:300}")
    private long streamTimeoutSeconds;

    private final ThreadPoolExecutor sseExecutor = new ThreadPoolExecutor(
            4,                                      // corePoolSize
            16,                                     // maxPoolSize
            60L, TimeUnit.SECONDS,                  // keepAlive
            new LinkedBlockingQueue<>(64),          // bounded queue → 背压
            r -> {
                Thread t = new Thread(r, "sse-agent-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行 → 自然限流
    );

    public AgentStreamService(ReactAgent agent, AgentMetrics metrics) {
        this.agent = agent;
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        metrics.registerThreadPool("sse-agent", sseExecutor);
    }

    /**
     * ★ 关闭线程池，防止资源泄漏
     */
    @PreDestroy
    public void destroy() {
        sseExecutor.shutdown();
        try {
            if (!sseExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                sseExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public SseEmitter streamChat(String question) {
        long startTime = System.currentTimeMillis();
        metrics.sseConnections.increment();
        log.info("Agent streamChat start, question={}", question);

        SseEmitter emitter = new SseEmitter(streamTimeoutSeconds * 1000L);
        emitter.onTimeout(() -> {
            metrics.sseTimeout.increment();
            log.warn("★ SSE 连接超时 — question={}", question);
        });
        emitter.onError(e -> log.warn("★ SSE 连接异常 — question={}", question, e));
        beginRecording(emitter);

        StreamingTextCollector collector = new StreamingTextCollector();

        sseExecutor.submit(() -> {
            try {
                sendEvent(emitter, StreamEvent.thinking("正在分析你的问题，匹配最合适的技能..."));

                agent.stream(question)
                        .doOnNext(nodeOutput -> handleNodeOutput(emitter, collector, nodeOutput))
                        .doOnComplete(() -> log.info("Stream done, answerLen={}", collector.length()))
                        .doOnError(e -> handleStreamError(emitter, e))
                        .blockLast();

                String answer = resolveAnswer(collector);
                if (!answer.isEmpty()) {
                    answer = deduplicate(answer);
                    boolean hadStreaming = collector.length() > 0;
                    if (!hadStreaming) {
                        sendEvent(emitter, StreamEvent.message(answer));
                    } else {
                        log.info("Skipping message event (streaming text already sent)");
                    }
                } else {
                    log.warn("No answer extracted!");
                }

                sendDoneEvent(emitter, question, startTime, answer);
                emitter.complete();
            } catch (Exception e) {
                log.error("Agent stream error", e);
                sendEvent(emitter, StreamEvent.error("error: " + e.getMessage()));
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void beginRecording(SseEmitter emitter) {
        LoggingToolCallback.beginRecording();
        NacosSkillRegistry.beginRecording();
        LoggingToolCallback.setSseEmitter(emitter);
        NacosSkillRegistry.setSseEmitter(emitter);
    }

    private void handleNodeOutput(SseEmitter emitter, StreamingTextCollector collector,
                                  NodeOutput nodeOutput) {
        if (nodeOutput.state() != null) {
            collector.setLastState(nodeOutput.state());
        }

        if (nodeOutput instanceof StreamingOutput<?> so) {
            String chunk = so.chunk();
            if (chunk != null && !chunk.isEmpty()) {
                String delta = extractDelta(collector, chunk);
                if (!delta.isEmpty()) {
                    collector.append(delta);
                    sendEvent(emitter, StreamEvent.text(delta));
                }
            }
        } else {
            log.info("Graph Node: node={}, isEND={}", nodeOutput.node(), nodeOutput.isEND());
            sendEvent(emitter, StreamEvent.node(nodeOutput.node(), nodeOutput.isEND()));
        }
    }

    /**
     * 基于位置追踪的增量提取（O(1) 而非 O(n) 子串比较）
     * <p>
     * 兼容两种 chunk 模式：
     * <ul>
     *   <li>累积模式：chunk 是完整文本，提取从 prevLength 之后的新内容</li>
     *   <li>增量模式：chunk 是纯增量，直接返回</li>
     * </ul>
     */
    String extractDelta(StreamingTextCollector collector, String chunk) {
        int prevLength = collector.length();
        if (prevLength == 0) {
            return chunk;
        }
        if (chunk.length() <= prevLength) {
            return chunk; // 纯增量模式：chunk 本身就是新增内容
        }
        return chunk.substring(prevLength); // 累积模式：提取增量部分
    }

    private String resolveAnswer(StreamingTextCollector collector) {
        String answer = collector.getAnswer();
        if (!answer.isEmpty()) {
            return answer;
        }
        OverAllState lastState = collector.getLastState();
        if (lastState == null) {
            return "";
        }
        answer = extractFromMessages(lastState);
        if (!answer.isEmpty()) {
            log.info("Extracted from messages, len={}", answer.length());
            return answer;
        }
        String outputKey = agent.getOutputKey();
        if (outputKey != null) {
            Object raw = lastState.data().get(outputKey);
            if (raw != null) {
                answer = raw.toString();
                log.info("Extracted from state data, key={}, len={}", outputKey, answer.length());
            }
        }
        return answer;
    }

    private void handleStreamError(SseEmitter emitter, Throwable e) {
        log.error("Stream error", e);
        sendEvent(emitter, StreamEvent.error("Agent error: " + e.getMessage()));
        emitter.completeWithError(e);
    }

    private void sendDoneEvent(SseEmitter emitter, String question, long startTime,
                               String answer) {
        List<ToolCallRecord> records = LoggingToolCallback.endRecording();
        List<String> calledSkills = NacosSkillRegistry.endRecording();
        long processingTime = System.currentTimeMillis() - startTime;

        metrics.sseCompleted.increment();
        metrics.sseDuration.record(processingTime, TimeUnit.MILLISECONDS);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("question", question);
        metadata.put("matchedSkill", calledSkills.isEmpty() ? null : calledSkills.get(0));
        metadata.put("calledSkills", calledSkills.isEmpty() ? List.of() : calledSkills);
        metadata.put("toolCallCount", records.size());
        metadata.put("processingTimeMs", processingTime);
        metadata.put("answerLength", answer.length());
        sendEvent(emitter, StreamEvent.done(metadata));

        log.info("Agent done: {}ms, skills={}, tools={}, answerLen={}",
                processingTime, calledSkills, records.size(), answer.length());
    }

    @SuppressWarnings("unchecked")
    private String extractFromMessages(OverAllState state) {
        try {
            Object messagesObj = state.data().get("messages");
            if (!(messagesObj instanceof List<?> rawList)) {
                log.info("messages is not a List, class={}",
                        messagesObj != null ? messagesObj.getClass().getName() : "null");
                return "";
            }
            List<Message> messages = (List<Message>) rawList;
            log.info("messages list size={}", messages.size());

            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AssistantMessage am) {
                    String text = am.getText();
                    if (text != null && !text.isBlank()) {
                        log.info("Found AssistantMessage at index {}, len={}", i, text.length());
                        return text.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract from messages: {}", e.getMessage());
        }
        return "";
    }

    private void sendEvent(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(event.toJson()));
        } catch (IOException e) {
            log.debug("SSE send failed: {}", e.getMessage());
        }
    }

    /**
     * 检测并去除重复文本（如 LLM 输出 "你好你好你好" 这种循环重复）
     * <p>
     * ★ 优化：只检查整除长度的重复模式（O(k) 其中 k = 因子数），而非 O(n²)
     */
    String deduplicate(String text) {
        if (text == null || text.length() < 4) {
            return text;
        }
        int len = text.length();
        // ★ 从大到小检查可能的最小重复单元长度（必须是 len 的因子）
        for (int half = len / 2; half >= Math.max(2, len / 10); half--) {
            if (len % half != 0) {
                continue;  // ★ 跳过非整除情况，大幅减少比较次数
            }
            String prefix = text.substring(0, half);
            boolean duplicated = true;
            for (int pos = half; pos < len; pos += half) {
                if (!text.startsWith(prefix, pos)) {
                    duplicated = false;
                    break;
                }
            }
            if (duplicated) {
                log.info("Dedup: {} -> {} chars", len, half);
                return prefix;
            }
        }
        return text;
    }

    private static class StreamingTextCollector {
        private final StringBuilder builder = new StringBuilder();
        private OverAllState lastState;

        void append(String delta) {
            builder.append(delta);
        }

        String getAnswer() {
            return builder.toString();
        }

        int length() {
            return builder.length();
        }

        void setLastState(OverAllState state) {
            this.lastState = state;
        }

        OverAllState getLastState() {
            return lastState;
        }
    }
}