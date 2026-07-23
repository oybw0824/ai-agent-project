package com.nbcb.agent.governance;

import com.github.benmanes.caffeine.cache.Cache;
import com.nbcb.agent.domain.RequestContext;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.domain.ToolCallRecord;
import com.nbcb.agent.util.SsePushHelper;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 统一工具拦截包装器 — 治理检查 + 结果缓存 + SSE 推送，全部在 call() 中。
 * <p>
 * 在 {@code primaryToolCallbackProvider} 中一次性包装所有工具，不依赖 Hook、不逐个装饰。
 *
 * @author com.nbcb
 */
@Slf4j
public class UnifiedToolWrapper implements ToolCallback {

    private final ToolCallback delegate;
    private final Cache<String, String> cache;
    private final ToolGovernanceInterceptor governance;
    private final boolean cacheable;

    private static final int MAX_IN = 500;
    private static final int MAX_OUT = 200;

    public UnifiedToolWrapper(ToolCallback delegate, Cache<String, String> cache,
                               ToolGovernanceInterceptor governance,
                               boolean cacheable) {
        this.delegate = delegate;
        this.cache = cache;
        this.governance = governance;
        this.cacheable = cacheable;
    }

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        return callInternal(toolInput, null, false);
    }

    @Override
    public @NonNull String call(@NonNull String toolInput, ToolContext toolContext) {
        return callInternal(toolInput, toolContext, true);
    }

    private String callInternal(String toolInput, ToolContext toolContext, boolean hasToolContext) {
        String toolName = delegate.getToolDefinition().name();

        // 1. 治理检查
        governance.checkEnabled(toolName);

        // 2. 每次逻辑调用都推送事件，缓存命中也不能从调用轨迹中消失
        pushSse(StreamEvent.toolCall(toolName, trunc(toolInput, MAX_IN)));

        // 3. 仅显式允许的只读、幂等工具可使用缓存
        String key = cacheable ? cacheKey(toolName, toolInput) : null;
        String cached = key == null ? null : cache.getIfPresent(key);
        if (cached != null) {
            pushSse(StreamEvent.toolResult(toolName, trunc(cached, MAX_OUT)));
            recordSuccess(toolName, toolInput, cached);
            log.info("★ 工具缓存命中 [{}]", toolName);
            return cached;
        }

        // 4. 真实调用
        long t0 = System.currentTimeMillis();
        String result;
        try {
            result = hasToolContext
                    ? delegate.call(toolInput, toolContext)
                    : delegate.call(toolInput);
        } catch (Exception e) {
            recordFailure(toolName, toolInput, e);
            log.warn("工具异常 [{}] {}ms", toolName, System.currentTimeMillis() - t0);
            throw e;
        }

        // 5. 调用后：SSE + 缓存 + 记录
        pushSse(StreamEvent.toolResult(toolName, trunc(result, MAX_OUT)));
        log.info("工具调用 [{}] {}ms", toolName, System.currentTimeMillis() - t0);
        if (key != null) {
            cache.put(key, result);
        }

        recordSuccess(toolName, toolInput, result);

        return result;
    }

    // ==================== 辅助 ====================

    private static String cacheKey(String name, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return name + ":" + HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return name + ":" + Integer.toHexString(input.hashCode());
        }
    }

    private void pushSse(StreamEvent e) {
        RequestContext ctx = RequestContext.current();
        if (ctx != null && ctx.getEmitter() != null) SsePushHelper.push(ctx.getEmitter(), e);
    }

    private void recordSuccess(String name, String in, String out) {
        RequestContext ctx = RequestContext.current();
        if (ctx == null) return;
        ToolCallRecord r = ToolCallRecord.builder().toolName(name).input(trunc(in, MAX_IN)).build();
        r.setOutput(trunc(out, MAX_OUT));
        r.setSuccess(true);
        ctx.recordToolCall(r);
    }

    private void recordFailure(String name, String in, Exception error) {
        RequestContext ctx = RequestContext.current();
        if (ctx == null) return;
        ToolCallRecord record = ToolCallRecord.builder()
                .toolName(name)
                .input(trunc(in, MAX_IN))
                .success(false)
                .error(trunc(error.getMessage(), MAX_OUT))
                .build();
        ctx.recordToolCall(record);
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
