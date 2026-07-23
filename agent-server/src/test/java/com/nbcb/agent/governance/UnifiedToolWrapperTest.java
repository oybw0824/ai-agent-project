package com.nbcb.agent.governance;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.nbcb.agent.domain.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一工具包装器测试，覆盖缓存白名单和调用轨迹语义。
 */
class UnifiedToolWrapperTest {

    private ToolGovernanceInterceptor governance;

    @BeforeEach
    void setUp() {
        ToolGovernanceProperties properties = new ToolGovernanceProperties();
        ToolGovernanceProperties.ToolPolicy policy = new ToolGovernanceProperties.ToolPolicy();
        policy.setStatus(ToolGovernanceProperties.ToolStatus.ENABLED);
        properties.getTools().put("testTool", policy);
        governance = new ToolGovernanceInterceptor(properties);
    }

    @AfterEach
    void tearDown() {
        RequestContext.cleanupAll();
    }

    @Test
    @DisplayName("缓存白名单工具命中缓存时仍记录两次逻辑调用")
    void shouldRecordCacheHitAsLogicalToolCall() {
        CountingTool delegate = new CountingTool();
        UnifiedToolWrapper wrapper = wrapper(delegate, true);

        try (RequestContext context = RequestContext.begin(null)) {
            assertThat(wrapper.call("{}")).isEqualTo("result-1");
            assertThat(wrapper.call("{}")).isEqualTo("result-1");

            assertThat(delegate.calls()).isEqualTo(1);
            assertThat(context.getToolRecords()).hasSize(2);
        }
    }

    @Test
    @DisplayName("未加入白名单的工具不缓存")
    void shouldNotCacheToolsOutsideAllowlist() {
        CountingTool delegate = new CountingTool();
        UnifiedToolWrapper wrapper = wrapper(delegate, false);

        wrapper.call("{}");
        wrapper.call("{}");

        assertThat(delegate.calls()).isEqualTo(2);
    }

    private UnifiedToolWrapper wrapper(ToolCallback delegate, boolean cacheable) {
        return new UnifiedToolWrapper(delegate, Caffeine.newBuilder().maximumSize(10).build(),
                governance, cacheable);
    }

    private static final class CountingTool implements ToolCallback {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("testTool")
                    .description("测试工具")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "result-" + callCount.incrementAndGet();
        }

        int calls() {
            return callCount.get();
        }
    }
}
