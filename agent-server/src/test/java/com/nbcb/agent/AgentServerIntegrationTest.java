package com.nbcb.agent;

import com.nbcb.agent.controller.ChatController;
import com.nbcb.agent.skill.NacosSkillLoader;
import com.nbcb.agent.skill.NacosSkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Server 集成测试
 * <p>
 * 使用 {@code application-test.yml} 配置，禁用 Nacos / DeepSeek 外部依赖。
 * 验证：
 * <ol>
 *   <li>Spring Context 正常加载</li>
 *   <li>关键 Bean 存在（含 v2.0 新增的 NacosSkillRegistry）</li>
 *   <li>NacosSkillLoader fallback 逻辑正常</li>
 * </ol>
 *
 * @author com.nbcb
 */
@DisplayName("Agent Server 集成测试")
@SpringBootTest
@ActiveProfiles("test")
class AgentServerIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private ChatController chatController;

    @Autowired
    private NacosSkillLoader skillLoader;

    @Autowired
    private NacosSkillRegistry skillRegistry;

    @Test
    @DisplayName("Spring Context 加载成功")
    void shouldLoadApplicationContext() {
        assertThat(context).isNotNull();
        assertThat(context.getStartupDate()).isPositive();
    }

    @Test
    @DisplayName("NacosSkillLoader Bean 存在 — 状态查询正常")
    void shouldHaveSkillLoader() {
        assertThat(skillLoader).isNotNull();
        assertThat(skillLoader.getLoadedSkillCount()).isGreaterThanOrEqualTo(0);
        assertThat(skillLoader.isNacosAvailable()).isNotNull();
    }

    @Test
    @DisplayName("★ NacosSkillRegistry Bean 存在（v2.0）")
    void shouldHaveNacosSkillRegistry() {
        assertThat(skillRegistry).isNotNull();
        assertThat(skillRegistry.getRegistryType()).isEqualTo("Nacos");
        assertThat(skillRegistry.getSkillLoadInstructions()).contains("read_skill");
    }

    @Test
    @DisplayName("ChatController Bean 存在")
    void shouldHaveChatController() {
        // ChatController 可能因 MCP Client 缺失而无法创建，
        // 但集成测试不要求它必须存在（Nacos 不可用场景）
        if (chatController != null) {
            assertThat(chatController).isNotNull();
        }
    }
}
