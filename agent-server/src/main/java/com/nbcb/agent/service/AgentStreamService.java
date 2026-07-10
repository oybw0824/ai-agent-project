package com.nbcb.agent.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.nbcb.agent.domain.RequestContext;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.domain.ToolCallRecord;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.stream.StreamEventHandler;
import com.nbcb.agent.service.stream.StreamingTextCollector;
import com.nbcb.agent.util.SsePushHelper;
import com.nbcb.agent.util.TextProcessingUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class AgentStreamService {

    private final ReactAgent agent;
    private final AgentMetrics metrics;
    private final ThreadPoolTaskExecutor sseExecutor;

    @Value("${agent.stream.timeout-seconds:300}")
    private long streamTimeoutSeconds;

    public AgentStreamService(ReactAgent agent, AgentMetrics metrics,
                              @Qualifier("sseTaskExecutor") ThreadPoolTaskExecutor sseExecutor) {
        this.agent = agent;
        this.metrics = metrics;
        this.sseExecutor = sseExecutor;
    }

    @PostConstruct
    public void init() {
        metrics.registerThreadPool("sse-agent", sseExecutor.getThreadPoolExecutor());
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
        emitter.onError(e -> {
            metrics.sseError.increment();
            log.warn("★ SSE 连接异常 — question={}", question, e);
        });
        emitter.onCompletion(() -> {
            log.debug("★ SSE 连接完成 — question={}", question);
        });

        StreamingTextCollector collector = new com.nbcb.agent.service.stream.StreamingTextCollector();

        // ★ 使用 subscribe 替代 blockLast，不阻塞线程，释放 sseExecutor
        sseExecutor.submit(() -> {
            // ★ 在 sseExecutor 线程内创建 RequestContext（注意：不可用 try-with-resources，
            //    因为 subscribe() 非阻塞，try 块会立即退出导致 FALLBACK 被清空，工具调用线程
            //    将无法获取上下文。必须在 doOnComplete/doOnError 中手动 close。）
            RequestContext context = RequestContext.begin(emitter);
            try {
                SsePushHelper.push(emitter, StreamEvent.thinking("正在分析你的问题，匹配最合适的技能..."));

                AtomicBoolean hasStreamingText = new AtomicBoolean(false);

                agent.stream(question)
                        .doOnNext(nodeOutput -> {
                            if (StreamEventHandler.handleNodeOutput(collector, nodeOutput,
                                    event -> SsePushHelper.push(emitter, event))) {
                                hasStreamingText.set(true);
                            }
                        })
                        .doOnComplete(() -> {
                            log.info("Stream done, answerLen={}", collector.length());
                            try {
                                String answer = resolveAnswer(collector);
                                if (!answer.isEmpty()) {
                                    answer = TextProcessingUtil.deduplicate(answer);
                                    if (!hasStreamingText.get()) {
                                        SsePushHelper.push(emitter, StreamEvent.message(answer));
                                    } else {
                                        log.info("Skipping message event (streaming text already sent)");
                                    }
                                } else {
                                    log.warn("No answer extracted!");
                                }
                                sendDoneEvent(emitter, question, startTime, answer);
                                emitter.complete();
                            } catch (Exception ex) {
                                handleStreamError(emitter, ex);
                            } finally {
                                context.close(); // ★ 流完成后清理 RequestContext
                            }
                        })
                        .doOnError(e -> {
                            handleStreamError(emitter, e);
                            context.close(); // ★ 出错时清理 RequestContext
                        })
                        .subscribe(); // ★ 非阻塞订阅，回调中处理完成/错误
            } catch (Exception e) {
                log.error("Agent stream error", e);
                metrics.sseError.increment();
                SsePushHelper.push(emitter, StreamEvent.error("error: " + e.getMessage()));
                emitter.completeWithError(e);
                context.close(); // ★ 同步异常时清理
            }
        });

        return emitter;
    }

    /**
     * ★ 处理流式错误
     *
     * @param emitter SSE 发射器
     * @param e       异常
     */
    private void handleStreamError(SseEmitter emitter, Throwable e) {
        log.error("Stream error", e);
        metrics.sseError.increment();
        SsePushHelper.push(emitter, StreamEvent.error("Agent error: " + e.getMessage()));
        emitter.completeWithError(e);
    }

    /**
     * ★ 从收集器中解析最终答案
     * <p>
     * 优先级：
     * <ol>
     *   <li>累积的流式文本</li>
     *   <li>从 messages 中提取最后的 AssistantMessage</li>
     *   <li>从 state data 中提取 outputKey 对应的值</li>
     * </ol>
     *
     * @param collector 文本收集器
     * @return 最终答案
     */
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

    /**
     * ★ 发送完成事件
     *
     * @param emitter SSE 发射器
     * @param question 用户问题
     * @param startTime 开始时间
     * @param answer   最终答案
     */
    private void sendDoneEvent(SseEmitter emitter, String question, long startTime,
                               String answer) {
        RequestContext ctx = RequestContext.current();
        List<ToolCallRecord> records = ctx != null ? ctx.getToolRecords() : List.of();
        List<String> calledSkills = ctx != null ? new ArrayList<>(ctx.getCalledSkills()) : List.of();
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
        SsePushHelper.push(emitter, StreamEvent.done(metadata));

        log.info("Agent done: {}ms, skills={}, tools={}, answerLen={}",
                processingTime, calledSkills, records.size(), answer.length());
    }

    /**
     * ★ 从状态中提取最后的 AssistantMessage
     *
     * @param state OverAllState
     * @return 消息文本，提取失败返回空字符串
     */
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
}