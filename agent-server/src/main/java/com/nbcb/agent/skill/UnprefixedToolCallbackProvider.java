package com.nbcb.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;

/**
 * ★ 去掉分布式客户端自动加的工具名前缀（如 m_s_）
 * <p>
 * 包装原始的 {@link ToolCallbackProvider}，将每个工具名中的前缀剥离，
 * 使 LLM 看到的工具名与 SKILL.md 中声明的工具名一致。
 * <p>
 * 前缀匹配规则：默认匹配 {@code [a-z]_[a-z]} 格式（如 m_s_），
 * 可通过构造函数参数自定义正则表达式。
 * <p>
 * ★ 优化：工具回调数组在构造时缓存，避免每次 {@link #getToolCallbacks()} 重复创建包装对象。
 *
 * @author nbcb
 */
@Slf4j
public class UnprefixedToolCallbackProvider implements ToolCallbackProvider {

    /** 默认前缀匹配模式：单字母_单字母（如 m_s） */
    private static final String DEFAULT_PREFIX_PATTERN = "[a-z]_[a-z]";

    private final ToolCallbackProvider delegate;
    private final String prefixPattern;

    /** ★ 缓存去前缀后的工具回调数组 */
    private final ToolCallback[] cachedCallbacks;

    public UnprefixedToolCallbackProvider(ToolCallbackProvider delegate) {
        this(delegate, DEFAULT_PREFIX_PATTERN);
    }

    public UnprefixedToolCallbackProvider(ToolCallbackProvider delegate, String prefixPattern) {
        this.delegate = delegate;
        this.prefixPattern = prefixPattern;
        this.cachedCallbacks = buildCallbacks();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return cachedCallbacks;
    }

    private ToolCallback[] buildCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
                .map(tc -> new UnprefixedToolCallback(tc, prefixPattern))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 单个工具回调包装 — 去掉前缀
     */
    @Slf4j
    private static class UnprefixedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String shortName;

        UnprefixedToolCallback(ToolCallback delegate, String prefixPattern) {
            this.delegate = delegate;
            String fullName = delegate.getToolDefinition().name();
            this.shortName = stripPrefix(fullName, prefixPattern);
        }

        /**
         * 根据正则模式剥离工具名前缀。
         * <p>
         * 例如：m_s_getWeatherByCity → getWeatherByCity
         *
         * @param fullName 完整工具名
         * @param pattern  前缀匹配正则
         * @return 去前缀后的工具名
         */
        private static String stripPrefix(String fullName, String pattern) {
            int idx = fullName.lastIndexOf('_');
            if (idx > 0 && idx < fullName.length() - 1) {
                String prefix = fullName.substring(0, idx);
                if (prefix.matches(pattern)) {
                    String shortName = fullName.substring(idx + 1);
                    log.debug("工具名去前缀: {} → {}", fullName, shortName);
                    return shortName;
                }
            }
            return fullName;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            ToolDefinition def = delegate.getToolDefinition();
            return DefaultToolDefinition.builder()
                    .name(shortName)
                    .description(def.description())
                    .inputSchema(def.inputSchema())
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        /** ★ 透传 ToolContext，避免框架调用 2-arg 版本时丢失上下文 */
        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}