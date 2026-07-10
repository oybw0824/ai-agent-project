package com.nbcb.agent;

import com.nbcb.agent.controller.ChatController;
import com.nbcb.agent.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Server 集成测试
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
    private SkillRegistry skillRegistry;

    @Test
    @DisplayName("Spring Context 加载成功")
    void shouldLoadApplicationContext() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("SkillRegistry Bean 存在")
    void shouldHaveSkillRegistry() {
        assertThat(skillRegistry).isNotNull();
        assertThat(skillRegistry.size()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("ChatController Bean 存在")
    void shouldHaveChatController() {
        assertThat(chatController).isNotNull();
    }
}
