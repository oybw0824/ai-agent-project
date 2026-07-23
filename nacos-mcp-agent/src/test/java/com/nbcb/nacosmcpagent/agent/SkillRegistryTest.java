package com.nbcb.nacosmcpagent.agent;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @Test
    void shouldLoadOnlyBoundSkillDirectoryBySkillCode()
            throws Exception {
        AgentSkillRegistryFactory factory = new AgentSkillRegistryFactory(
                new DefaultResourceLoader(),
                "skills");

        SkillRegistry registry = factory.create(
                "credit-agent",
                List.of("enterprise-credit-query"));

        assertThat(registry.contains("enterprise-credit-query")).isTrue();
        assertThat(registry.contains("local-weather-query")).isFalse();
        assertThat(registry.readSkillContent("enterprise-credit-query"))
                .contains("queryEnterpriseCredit");
        assertThat(registry.getRegistryType())
                .isEqualTo("DatabaseBoundClasspathSkill");
    }

    @Test
    void shouldSkipMissingSkillDirectory() {
        AgentSkillRegistryFactory factory = new AgentSkillRegistryFactory(
                new DefaultResourceLoader(),
                "skills");

        SkillRegistry registry = factory.create(
                "credit-agent",
                List.of("missing-skill"));

        assertThat(registry.contains("missing-skill")).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    void shouldUseSkillCodeAsNameWithoutFrontmatterName()
            throws Exception {
        AgentSkillRegistryFactory factory = new AgentSkillRegistryFactory(
                new SingleSkillResourceLoader(),
                "skills");

        SkillRegistry registry = factory.create(
                "credit-agent",
                List.of("name-free-skill"));

        assertThat(registry.contains("name-free-skill")).isTrue();
        assertThat(registry.readSkillContent("name-free-skill"))
                .contains("示例技能内容");
    }

    private static final class SingleSkillResourceLoader
            implements ResourceLoader {

        @Override
        public Resource getResource(String location) {
            if (!"classpath:skills/name-free-skill/SKILL.md"
                    .equals(location)) {
                return new DefaultResourceLoader().getResource(location);
            }
            String content = """
                    ---
                    description: 不声明 name 的测试技能
                    ---

                    示例技能内容
                    """;
            return new ByteArrayResource(
                    content.getBytes(StandardCharsets.UTF_8)) {

                @Override
                public String getDescription() {
                    return location;
                }
            };
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}
