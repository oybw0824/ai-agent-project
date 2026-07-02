package com.nbcb.agent.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * NacosSkillRegistry 单元测试
 * <p>
 * 验证 Nacos AI Registry → 框架 SkillRegistry 桥接的正确性。
 */
@DisplayName("NacosSkillRegistry 单元测试")
@ExtendWith(MockitoExtension.class)
class NacosSkillRegistryTest {

    @Mock
    private NacosSkillLoader skillLoader;

    private NacosSkillRegistry registry;

    private static NacosSkillMeta createTestSkill(
            String name, String desc, List<String> tools, String instructions) {
        NacosSkillMeta meta = new NacosSkillMeta();
        meta.setName(name);
        meta.setDescription(desc);
        meta.setVersion("1.0.0");
        meta.setTools(tools);
        meta.setInstructions(instructions);
        // 模拟 rawContent
        meta.setRawContent("---\nname: " + name + "\ndescription: " + desc
                + "\ntools:\n  - " + String.join("\n  - ", tools)
                + "\n---\n\n" + instructions);
        return meta;
    }

    @BeforeEach
    void setUp() {
        // 模拟 NacosSkillLoader 中已加载的技能
        NacosSkillMeta weatherSkill = createTestSkill(
                "weather", "天气查询技能",
                List.of("getWeatherByCity"),
                "# 天气查询\n## 执行流程\n1. 调用工具\n2. 返回结果");

        NacosSkillMeta calcSkill = createTestSkill(
                "calculator", "数值计算技能",
                List.of("calculate"),
                "# 数值计算\n## 执行流程\n1. 识别表达式\n2. 调用 calculate");

        when(skillLoader.getLoadedSkills())
                .thenReturn(List.of("weather", "calculator"));
        when(skillLoader.getSkill("weather")).thenReturn(weatherSkill);
        when(skillLoader.getSkill("calculator")).thenReturn(calcSkill);

        registry = new NacosSkillRegistry(skillLoader);
    }

    @Test
    @DisplayName("应返回正确的注册表类型")
    void shouldReturnRegistryType() {
        assertThat(registry.getRegistryType()).isEqualTo("Nacos");
    }

    @Test
    @DisplayName("应注册所有已加载的技能")
    void shouldRegisterAllLoadedSkills() {
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    @DisplayName("contains → 已注册技能返回 true")
    void shouldContainRegisteredSkill() {
        assertThat(registry.contains("weather")).isTrue();
        assertThat(registry.contains("calculator")).isTrue();
        assertThat(registry.contains("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("get → 已注册技能返回 SkillMetadata")
    void shouldGetRegisteredSkill() {
        Optional<SkillMetadata> meta = registry.get("weather");
        assertThat(meta).isPresent();
        assertThat(meta.get().getName()).isEqualTo("weather");
        assertThat(meta.get().getDescription()).isEqualTo("天气查询技能");
        assertThat(meta.get().getSource()).isEqualTo("nacos");
        assertThat(meta.get().getSkillPath()).isEqualTo("/nacos/skills/weather");
    }

    @Test
    @DisplayName("get → 未注册技能返回 empty")
    void shouldReturnEmptyForUnknownSkill() {
        assertThat(registry.get("unknown")).isEmpty();
    }

    @Test
    @DisplayName("listAll → 返回所有已注册技能")
    void shouldListAllSkills() {
        List<SkillMetadata> all = registry.listAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(SkillMetadata::getName)
                .containsExactlyInAnyOrder("weather", "calculator");
    }

    @Test
    @DisplayName("readSkillContent → 返回完整 SKILL.md 内容")
    void shouldReadSkillContent() throws IOException {
        String content = registry.readSkillContent("weather");
        assertThat(content).isNotNull();
        assertThat(content).contains("name: weather");
        assertThat(content).contains("# 天气查询");
        assertThat(content).contains("---"); // YAML frontmatter
    }

    @Test
    @DisplayName("readSkillContent → 技能不存在时抛出 IOException")
    void shouldThrowOnUnknownSkill() {
        assertThatThrownBy(() -> registry.readSkillContent("unknown"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("未在 Nacos 注册表中找到");
    }

    @Test
    @DisplayName("reload → 清空并重新加载技能")
    void shouldReloadSkills() {
        assertThat(registry.size()).isEqualTo(2);

        // 模拟技能列表变化
        NacosSkillMeta newSkill = createTestSkill(
                "new-skill", "新技能",
                List.of("someTool"),
                "# 新技能");
        when(skillLoader.getLoadedSkills()).thenReturn(List.of("new-skill"));
        when(skillLoader.getSkill("new-skill")).thenReturn(newSkill);

        registry.reload();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.contains("new-skill")).isTrue();
        assertThat(registry.contains("weather")).isFalse(); // 被清除
    }

    @Test
    @DisplayName("getSkillLoadInstructions → 返回非空指令")
    void shouldReturnLoadInstructions() {
        String instructions = registry.getSkillLoadInstructions();
        assertThat(instructions).isNotBlank();
        assertThat(instructions).contains("read_skill");
    }

    @Test
    @DisplayName("getSystemPromptTemplate → 返回非空模板")
    void shouldReturnSystemPromptTemplate() {
        assertThat(registry.getSystemPromptTemplate()).isNotNull();
    }

    @Test
    @DisplayName("getRegisteredSkillNames → 返回排序后的技能名列表")
    void shouldReturnSortedSkillNames() {
        List<String> names = registry.getRegisteredSkillNames();
        assertThat(names).containsExactly("calculator", "weather"); // 字母顺序
    }

    @Test
    @DisplayName("skillPath 格式为 /nacos/skills/{name}")
    void shouldHaveNacosSkillPath() {
        SkillMetadata meta = registry.get("calculator").orElseThrow();
        assertThat(meta.getSkillPath()).isEqualTo("/nacos/skills/calculator");
    }

    @Test
    @DisplayName("fullContent 不为空")
    void shouldHaveNonEmptyFullContent() {
        SkillMetadata meta = registry.get("weather").orElseThrow();
        assertThat(meta.getFullContent()).isNotBlank();
    }
}
