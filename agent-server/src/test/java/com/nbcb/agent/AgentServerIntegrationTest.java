package com.nbcb.agent;

import com.nbcb.agent.controller.ChatController;
import com.nbcb.agent.skill.dynamic.AgentSkillLocalStore;
import com.nbcb.agent.skill.dynamic.AgentSkillSnapshot;
import com.nbcb.agent.skill.dynamic.DynamicSkillProperties;
import com.nbcb.agent.skill.dynamic.DynamicSkillRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent Server 集成测试
 */
@DisplayName("Agent Server 集成测试")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentServerIntegrationTest {

    private static final Path NAS_ROOT = Path.of("target", "test-nas-integration")
            .toAbsolutePath().normalize();

    @DynamicPropertySource
    static void dynamicSkillProperties(DynamicPropertyRegistry registry) {
        registry.add("agent.skill.dynamic.nas-root", () -> NAS_ROOT.toString());
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:agent_governance_integration;MODE=Oracle;"
                        + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private ChatController chatController;

    @Autowired
    private DynamicSkillRuntimeService dynamicSkillRuntimeService;

    @Autowired
    private AgentSkillLocalStore agentSkillLocalStore;

    @Autowired
    private DynamicSkillProperties dynamicSkillProperties;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanSkillBindings() throws IOException {
        Files.createDirectories(NAS_ROOT);
        jdbcTemplate.update("DELETE FROM agent_skill_binding");
        agentSkillLocalStore.replaceSnapshot("skill-agent",
                new AgentSkillSnapshot("skill-agent", Map.of()));
    }

    @Test
    @DisplayName("Spring Context 加载成功")
    void shouldLoadApplicationContext() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("动态 Skill 运行时 Bean 存在")
    void shouldHaveDynamicSkillRuntime() {
        assertThat(dynamicSkillRuntimeService).isNotNull();
        assertThat(dynamicSkillProperties.getAgentName()).isEqualTo("skill-agent");
    }

    @Test
    @DisplayName("ChatController Bean 存在")
    void shouldHaveChatController() {
        assertThat(chatController).isNotNull();
    }

    @Test
    @DisplayName("指标与 Actuator 端点已移除")
    void shouldNotExposeMonitoringEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("快照未加载时聊天接口返回 503")
    void shouldRejectChatWhenSkillSnapshotIsNotReady() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"测试问题\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SKILL_SNAPSHOT_NOT_READY"));
    }

    @Test
    @DisplayName("H2 绑定、NAS 版本切换和 Reload 接口保持一致")
    void shouldLoadVersionedSkillFromDatabaseAndNas() throws Exception {
        writeSkill("integration-skill", "1.0.0", "第一版规则");
        insertBinding("integration-skill", "1.0.0");

        mockMvc.perform(post("/api/agents/skill-agent/skills/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELOADED"))
                .andExpect(jsonPath("$.skillCount").value(1))
                .andExpect(jsonPath("$.skills[0].skillName").value("integration-skill"))
                .andExpect(jsonPath("$.skills[0].version").value("1.0.0"));

        AgentSkillSnapshot oldRequest = dynamicSkillRuntimeService.currentSnapshot("skill-agent");
        assertThat(oldRequest.get("integration-skill").orElseThrow().definition().content())
                .contains("第一版规则");

        writeSkill("integration-skill", "2.0.0", "第二版规则");
        jdbcTemplate.update("""
                UPDATE agent_skill_binding
                   SET skill_version = ?, skill_file_path = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE agent_name = ? AND skill_name = ?
                """, "2.0.0", skillPath("integration-skill", "2.0.0"),
                "skill-agent", "integration-skill");

        mockMvc.perform(post("/api/agents/skill-agent/skills/integration-skill/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentName").value("skill-agent"))
                .andExpect(jsonPath("$.skillName").value("integration-skill"))
                .andExpect(jsonPath("$.version").value("2.0.0"))
                .andExpect(jsonPath("$.status").value("RELOADED"));

        AgentSkillSnapshot newRequest = dynamicSkillRuntimeService.currentSnapshot("skill-agent");
        assertThat(oldRequest.get("integration-skill").orElseThrow().definition().content())
                .contains("第一版规则");
        assertThat(newRequest.get("integration-skill").orElseThrow().definition().content())
                .contains("第二版规则");

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.skills[0].loadedVersion").value("2.0.0"));
    }

    private void insertBinding(String skillName, String version) {
        jdbcTemplate.update("""
                INSERT INTO agent_skill_binding
                    (agent_name, skill_name, skill_version, skill_file_path, enabled)
                VALUES (?, ?, ?, ?, TRUE)
                """, "skill-agent", skillName, version, skillPath(skillName, version));
    }

    private void writeSkill(String skillName, String version, String content) throws IOException {
        Path skillFile = NAS_ROOT.resolve(skillPath(skillName, version));
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: %s
                description: 集成测试技能
                version: %s
                ---
                %s
                """.formatted(skillName, version, content), StandardCharsets.UTF_8);
    }

    private String skillPath(String skillName, String version) {
        return skillName + "/" + version + "/SKILL.md";
    }
}
